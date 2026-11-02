// StoreInstallService.java — binder entry for OverDrive Store's silent installs
package com.overdrive.app.store;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import java.io.IOException;
import java.util.Arrays;

/**
 * Bound by OverDrive Store to install, update and uninstall apps without a prompt. Binding
 * needs the signature permission {@code com.overdrive.app.permission.STORE_INSTALL} (set on
 * the service in the manifest); each call additionally checks that the caller is
 * {@link StoreInstaller#STORE_PACKAGE} signed with OverDrive's own key. The work itself is
 * {@link StoreInstaller}.
 */
public final class StoreInstallService extends Service {
    private static final String TAG = "StoreInstallService";
    static final int API_VERSION = 1;

    private final IStoreInstaller.Stub binder = new IStoreInstaller.Stub() {
        @Override
        public int apiVersion() {
            return API_VERSION;
        }

        @Override
        public void install(ParcelFileDescriptor apk, String sha256, String packageName,
                            IStoreInstallCallback callback) {
            if (!callerIsStore()) {
                closeQuietly(apk);
                reply(callback, false, "caller is not OverDrive Store");
                return;
            }
            StoreInstaller.get(StoreInstallService.this).install(apk, sha256, packageName,
                    (success, message) -> reply(callback, success, message));
        }

        @Override
        public void uninstall(String packageName, IStoreInstallCallback callback) {
            if (!callerIsStore()) {
                reply(callback, false, "caller is not OverDrive Store");
                return;
            }
            StoreInstaller.get(StoreInstallService.this).uninstall(packageName,
                    (success, message) -> reply(callback, success, message));
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    /** The binder caller is the store package, signed with the same key as OverDrive. */
    private boolean callerIsStore() {
        int uid = Binder.getCallingUid();
        PackageManager pm = getPackageManager();
        String[] packages = pm.getPackagesForUid(uid);
        boolean isStore = packages != null
                && Arrays.asList(packages).contains(StoreInstaller.STORE_PACKAGE)
                && pm.checkSignatures(uid, Process.myUid()) == PackageManager.SIGNATURE_MATCH;
        if (!isStore) Log.w(TAG, "refused binder call from uid " + uid);
        return isStore;
    }

    private static void reply(IStoreInstallCallback callback, boolean success, String message) {
        if (callback == null) return;
        try {
            callback.onResult(success, message);
        } catch (RemoteException e) {
            Log.w(TAG, "store went away before the result: " + e.getMessage());
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
