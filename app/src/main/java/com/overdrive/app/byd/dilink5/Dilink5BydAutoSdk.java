// Dilink5BydAutoSdk.java — ParkCam-style access to BYDAuto devices on DiLink 5 (app process)
package com.overdrive.app.byd.dilink5;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;

/**
 * Shared ParkCam recipe for reaching BYDAuto devices on DiLink 5 (firmware 51.x), used by
 * {@link Dilink5GearReader} and {@link Dilink5PowerReader}:
 * <ul>
 *   <li>{@code /system/app/BydAutoService/BydAutoService.apk} in its own
 *       {@link dalvik.system.PathClassLoader};</li>
 *   <li>a Context whose {@code getApplicationContext()} returns itself (the device managers call
 *       {@code getApplicationContext().checkCallingOrSelfPermission()} in their constructors, which
 *       would otherwise strip the wrapper) and which grants {@code android.permission.BYDAUTO_*}.</li>
 * </ul>
 * App process only: as uid 2000 the BYDAuto services reject the caller.
 */
final class Dilink5BydAutoSdk {
    static final String SDK_APK = "/system/app/BydAutoService/BydAutoService.apk";
    static final String SDK_LIB_DIR = "/system/lib64";

    private static ClassLoader loader;

    private Dilink5BydAutoSdk() {}

    /** {@code <deviceClass>.getInstance(context)} through the SDK loader; throws on any failure. */
    static synchronized Object device(Context context, String deviceClass) throws Exception {
        if (loader == null) {
            loader = new dalvik.system.PathClassLoader(
                    SDK_APK, SDK_LIB_DIR, Dilink5BydAutoSdk.class.getClassLoader());
        }
        Class<?> cls = Class.forName(deviceClass, true, loader);
        Object device = cls.getMethod("getInstance", Context.class).invoke(null, bypassContext(context));
        if (device == null) throw new IllegalStateException(deviceClass + ".getInstance returned null");
        return device;
    }

    static Context bypassContext(Context context) {
        Context base = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        return new ContextWrapper(base) {
            @Override public Context getApplicationContext() { return this; }

            @Override public int checkCallingOrSelfPermission(String permission) {
                if (permission != null && permission.startsWith("android.permission.BYDAUTO_")) {
                    return PackageManager.PERMISSION_GRANTED;
                }
                return super.checkCallingOrSelfPermission(permission);
            }

            @Override public void enforceCallingOrSelfPermission(String permission, String message) {
                if (permission != null && permission.startsWith("android.permission.BYDAUTO_")) return;
                super.enforceCallingOrSelfPermission(permission, message);
            }
        };
    }

    static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
