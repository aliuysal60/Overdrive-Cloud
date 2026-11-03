// StoreInstaller.java — installs/uninstalls OverDrive Store's apps through the ADB shell (uid 2000)
package com.overdrive.app.store;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.overdrive.app.launcher.AdbShellExecutor;
import com.overdrive.app.updater.AppUpdater;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Silent install and uninstall for OverDrive Store. The store hands over an APK it has
 * downloaded; this copies it into the app cache while hashing it, checks the hash and the
 * package the APK declares, and streams it to the package manager over ADB
 * ({@code cmd package install -r -d}, what {@code adb install -r -d} does) as the shell
 * (uid 2000), like OverDrive's own updates, under the same single-install gate
 * ({@link AppUpdater#tryBeginInstall()}). Nothing is staged in /data/local/tmp: BYD's adbd
 * refuses file pushes there.
 *
 * <p>OverDrive itself, the store, the launcher companion and system packages are never
 * installed over or uninstalled from the store's requests; OverDrive installs and updates
 * the store itself through {@link #installStore}. Requests run one at a time on a private
 * thread.
 */
public final class StoreInstaller {
    private static final String TAG = "StoreInstaller";

    /** OverDrive Store, the Droid-ify fork (F-Droid apps). */
    public static final String STORE_PACKAGE = "com.overdrive.store";
    /** OverDrive Play Store, the Aurora Store fork (Google Play apps). */
    public static final String AURORA_PACKAGE = "com.overdrive.aurora";
    /** OverDrive's stores: the only callers StoreInstallService serves. */
    static final String[] STORE_PACKAGES = {STORE_PACKAGE, AURORA_PACKAGE};
    /** Packages OverDrive manages itself; a store may not replace or remove them. */
    static final String[] PROTECTED_PACKAGES = {
            "com.overdrive.app", STORE_PACKAGE, AURORA_PACKAGE, "com.overdrive.launcher"};
    static final long MAX_APK_BYTES = 1024L * 1024L * 1024L;
    static final long MAX_TOTAL_BYTES = 4L * 1024L * 1024L * 1024L;
    /** At most this many files in one split APK set. */
    static final int MAX_SPLITS = 64;

    private static final Pattern PACKAGE_NAME =
            Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern PM_FAILURE = Pattern.compile("Failure \\[([^\\]]*)\\]");

    // -r replace, -d allow downgrade: the same flags as OverDrive's own update, so the
    // store can also install an older version of an app the user picked.
    private static final String[] INSTALL_OPTIONS = {"-r", "-d"};
    private static final long PM_DEADLINE_MS = 300_000L;
    // A split set (Play apps) can be several hundred MB in one session.
    private static final long PM_MULTIPLE_DEADLINE_MS = 600_000L;
    // The executor guarantees a callback by its deadline; the margin only covers delivery.
    private static final long WAIT_MARGIN_MS = 20_000L;

    public interface ResultCallback {
        void onResult(boolean success, String message);
    }

    /** One request's result. */
    static final class Outcome {
        final boolean success;
        final String message;

        private Outcome(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        static Outcome ok() { return new Outcome(true, "Success"); }
        static Outcome fail(String message) { return new Outcome(false, message); }
    }

    private static volatile StoreInstaller instance;

    public static StoreInstaller get(Context context) {
        StoreInstaller local = instance;
        if (local == null) {
            synchronized (StoreInstaller.class) {
                local = instance;
                if (local == null) {
                    local = new StoreInstaller(context.getApplicationContext());
                    instance = local;
                }
            }
        }
        return local;
    }

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "StoreInstaller");
        t.setDaemon(true);
        return t;
    });
    private AdbShellExecutor adb; // created lazily, used only on the executor thread

    private StoreInstaller(Context context) {
        this.context = context;
    }

    /** Install or update {@code packageName} from {@code apk}; closes {@code apk}. */
    public void install(ParcelFileDescriptor apk, String sha256, String packageName,
                        ResultCallback callback) {
        ParcelFileDescriptor[] apks = apk == null ? null : new ParcelFileDescriptor[] {apk};
        executor.execute(() -> deliver(callback, packageName, "install",
                runInstall(apks, new String[] {sha256}, packageName)));
    }

    /**
     * Install or update {@code packageName} from a split APK set in one session;
     * {@code sha256s[i]} is the hash of {@code apks[i]}. Closes every descriptor.
     */
    public void installMultiple(ParcelFileDescriptor[] apks, String[] sha256s, String packageName,
                                ResultCallback callback) {
        executor.execute(() -> deliver(callback, packageName, "install",
                runInstall(apks, sha256s, packageName)));
    }

    public void uninstall(String packageName, ResultCallback callback) {
        executor.execute(() -> deliver(callback, packageName, "uninstall",
                runUninstall(packageName)));
    }

    /**
     * Install or update one of OverDrive's stores ({@link #STORE_PACKAGES}) from {@code apk},
     * which OverDrive downloaded ({@link StoreLauncher}); deletes {@code apk}. In-process only —
     * the binder service never reaches this, and a store may not replace itself through
     * {@link #install}.
     */
    public void installStore(File apk, String storePackage, ResultCallback callback) {
        executor.execute(() -> deliver(callback, storePackage, "install",
                runInstallStore(apk, storePackage)));
    }

    private Outcome runInstallStore(File apk, String storePackage) {
        try {
            if (!isStorePackage(storePackage)) return Outcome.fail(storePackage + " is not a store");
            String refusal = refuseArchivePackages(storePackage, Collections.singletonList(apk));
            if (refusal != null) return Outcome.fail(refusal);
            if (!AppUpdater.tryBeginInstall()) return Outcome.fail("another install is in progress");
            try {
                return pmInstall(Collections.singletonList(apk));
            } finally {
                AppUpdater.endInstall();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.fail("interrupted");
        } finally {
            if (apk.exists() && !apk.delete()) Log.w(TAG, "could not delete " + apk);
        }
    }

    // ---------------------------------------------------------------- request checks

    /** Why {@code packageName} may not be touched from the store, or null when it may. */
    static String refusePackageName(String packageName) {
        if (packageName == null || packageName.length() > 255
                || !PACKAGE_NAME.matcher(packageName).matches()) {
            return "invalid package name";
        }
        for (String managed : PROTECTED_PACKAGES) {
            if (managed.equals(packageName)) return packageName + " is managed by OverDrive";
        }
        return null;
    }

    static String refuseInstallRequest(String packageName, String sha256) {
        return refuseInstallRequest(packageName, new String[] {sha256}, 1);
    }

    /** Why an install of {@code apkCount} files with these hashes is refused, or null. */
    static String refuseInstallRequest(String packageName, String[] sha256s, int apkCount) {
        String refusal = refusePackageName(packageName);
        if (refusal != null) return refusal;
        if (apkCount < 1) return "no APK";
        if (apkCount > MAX_SPLITS) return "too many APKs";
        if (sha256s == null || sha256s.length != apkCount) {
            return "SHA-256 count does not match the APKs";
        }
        for (String sha256 : sha256s) {
            if (sha256 == null || !SHA256_HEX.matcher(sha256).matches()) return "invalid SHA-256";
        }
        return null;
    }

    static boolean isStorePackage(String packageName) {
        for (String store : STORE_PACKAGES) {
            if (store.equals(packageName)) return true;
        }
        return false;
    }

    /**
     * Why these APKs are not {@code packageName}, or null. A config split may not parse on
     * its own (null archive) and is skipped — pm checks the set's consistency at commit — but
     * every APK that does parse must declare {@code packageName}, and at least one must.
     */
    private String refuseArchivePackages(String packageName, List<File> apks) {
        boolean declared = false;
        for (File apk : apks) {
            PackageInfo archive = context.getPackageManager()
                    .getPackageArchiveInfo(apk.getPath(), 0);
            if (archive == null) continue;
            if (!packageName.equals(archive.packageName)) {
                return "APK is " + archive.packageName + ", not " + packageName;
            }
            declared = true;
        }
        return declared ? null : "not a valid APK";
    }

    /** Why the INSTALLED {@code packageName} may not be changed, or null (also when absent). */
    private String refuseInstalledPackage(String packageName) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(packageName, 0);
            int system = ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
            return (info.flags & system) != 0 ? packageName + " is a system app" : null;
        } catch (PackageManager.NameNotFoundException absent) {
            return null;
        }
    }

    private boolean isInstalled(String packageName) {
        try {
            context.getPackageManager().getApplicationInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException absent) {
            return false;
        }
    }

    // ---------------------------------------------------------------- install

    /** One APK or a split set: copy + hash each, check the package, install in one session. */
    private Outcome runInstall(ParcelFileDescriptor[] apks, String[] sha256s, String packageName) {
        List<File> local = new ArrayList<>();
        try {
            String refusal = refuseInstallRequest(packageName, sha256s,
                    apks == null ? 0 : apks.length);
            if (refusal == null) {
                for (ParcelFileDescriptor apk : apks) {
                    if (apk == null) refusal = "no APK";
                }
            }
            if (refusal == null) refusal = refuseInstalledPackage(packageName);
            if (refusal != null) return Outcome.fail(refusal);

            File dir = new File(context.getCacheDir(), "store_install");
            if (!dir.isDirectory() && !dir.mkdirs()) return Outcome.fail("cache unavailable");
            long stamp = System.nanoTime();
            long total = 0;
            for (int i = 0; i < apks.length; i++) {
                File file = new File(dir, "install-" + stamp + "-" + i + ".apk");
                local.add(file);
                String actual;
                try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(apks[i])) {
                    apks[i] = null; // the stream owns (and closes) it now
                    actual = copyAndHash(in, file, MAX_APK_BYTES);
                }
                if (!actual.equalsIgnoreCase(sha256s[i])) return Outcome.fail("SHA-256 mismatch");
                total += file.length();
                if (total > MAX_TOTAL_BYTES) {
                    return Outcome.fail("APKs larger than " + MAX_TOTAL_BYTES + " bytes");
                }
            }
            refusal = refuseArchivePackages(packageName, local);
            if (refusal != null) return Outcome.fail(refusal);

            if (!AppUpdater.tryBeginInstall()) return Outcome.fail("another install is in progress");
            try {
                return pmInstall(local);
            } finally {
                AppUpdater.endInstall();
            }
        } catch (IOException e) {
            return Outcome.fail("could not read APK (" + e.getMessage() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.fail("interrupted");
        } finally {
            if (apks != null) {
                for (ParcelFileDescriptor apk : apks) closeQuietly(apk);
            }
            for (File file : local) {
                if (file.exists() && !file.delete()) Log.w(TAG, "could not delete " + file);
            }
        }
    }

    /**
     * Stream the APK(s) to the package manager over ADB: one file like
     * {@code adb install -r -d}, a split set like {@code adb install-multiple -r -d}.
     */
    private Outcome pmInstall(List<File> apks) throws InterruptedException {
        ShellOutcome installed;
        if (apks.size() == 1) {
            installed = await(cb -> adb().installBulk(
                            apks.get(0), INSTALL_OPTIONS, PM_DEADLINE_MS, cb),
                    PM_DEADLINE_MS + WAIT_MARGIN_MS);
        } else {
            installed = await(cb -> adb().installMultipleBulk(
                            apks, INSTALL_OPTIONS, PM_MULTIPLE_DEADLINE_MS, cb),
                    PM_MULTIPLE_DEADLINE_MS + WAIT_MARGIN_MS);
        }
        return installed.ok ? Outcome.ok() : pmOutcome(installed.text);
    }

    // ---------------------------------------------------------------- uninstall

    private Outcome runUninstall(String packageName) {
        String refusal = refusePackageName(packageName);
        if (refusal == null) refusal = refuseInstalledPackage(packageName);
        if (refusal == null && !isInstalled(packageName)) refusal = packageName + " is not installed";
        if (refusal != null) return Outcome.fail(refusal);

        if (!AppUpdater.tryBeginInstall()) return Outcome.fail("another install is in progress");
        try {
            ShellOutcome removed = await(cb -> adb().executeBulk(
                    "pm uninstall " + packageName + " 2>&1", PM_DEADLINE_MS, cb),
                    PM_DEADLINE_MS + WAIT_MARGIN_MS);
            return pmOutcome(removed.text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.fail("interrupted");
        } finally {
            AppUpdater.endInstall();
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Copy {@code in} to {@code out}, refusing more than {@code maxBytes}; returns the
     * lowercase hex SHA-256 of what was copied.
     */
    static String copyAndHash(InputStream in, File out, long maxBytes) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (OutputStream os = new FileOutputStream(out)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > maxBytes) throw new IOException("APK larger than " + maxBytes + " bytes");
                digest.update(buffer, 0, n);
                os.write(buffer, 0, n);
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    /**
     * Read {@code pm install} / {@code pm uninstall} output: success only on a "Success"
     * line; otherwise the bracketed failure code, else the raw text.
     */
    static Outcome pmOutcome(String output) {
        String text = output == null ? "" : output.trim();
        for (String line : text.split("\\r?\\n")) {
            if (line.trim().equals("Success")) return Outcome.ok();
        }
        java.util.regex.Matcher failure = PM_FAILURE.matcher(text);
        if (failure.find()) return Outcome.fail(failure.group(1).trim());
        if (text.isEmpty()) return Outcome.fail("pm gave no answer");
        return Outcome.fail(text.length() > 300 ? text.substring(0, 300) : text);
    }

    private AdbShellExecutor adb() {
        if (adb == null) adb = new AdbShellExecutor(context);
        return adb;
    }

    /** Shell callback result: ok/text (output on success, error on failure). */
    private static final class ShellOutcome {
        final boolean ok;
        final String text;

        ShellOutcome(boolean ok, String text) {
            this.ok = ok;
            this.text = text == null ? "" : text;
        }
    }

    private static ShellOutcome await(Consumer<AdbShellExecutor.ShellCallback> start, long timeoutMs)
            throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<ShellOutcome> result = new AtomicReference<>();
        start.accept(new AdbShellExecutor.ShellCallback() {
            @Override public void onSuccess(String output) {
                result.compareAndSet(null, new ShellOutcome(true, output));
                done.countDown();
            }

            @Override public void onError(String error) {
                result.compareAndSet(null, new ShellOutcome(false, error));
                done.countDown();
            }
        });
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) return new ShellOutcome(false, "timed out");
        return result.get();
    }

    private static void deliver(ResultCallback callback, String packageName, String action,
                                Outcome outcome) {
        if (outcome.success) {
            Log.i(TAG, action + " " + packageName + ": Success");
        } else {
            Log.w(TAG, action + " " + packageName + " failed: " + outcome.message);
        }
        if (callback == null) return;
        try {
            callback.onResult(outcome.success, outcome.message);
        } catch (RuntimeException e) {
            Log.w(TAG, "result delivery failed: " + e.getMessage());
        }
    }

    private static void closeQuietly(ParcelFileDescriptor fd) {
        if (fd == null) return;
        try {
            fd.close();
        } catch (IOException ignored) {
        }
    }
}
