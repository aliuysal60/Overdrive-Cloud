// IStoreInstaller.aidl — OverDrive stores' silent install/uninstall entry into OverDrive
package com.overdrive.app.store;

import com.overdrive.app.store.IStoreInstallCallback;

/**
 * Bound by OverDrive's stores only (OverDrive Store com.overdrive.store, OverDrive Play Store
 * com.overdrive.aurora): the service requires the signature permission
 * com.overdrive.app.permission.STORE_INSTALL. OverDrive installs through its ADB shell
 * (uid 2000), like its own updates, so the user never sees an install prompt. Every call
 * answers exactly once on the callback.
 *
 * Methods are only ever APPENDED: transaction codes follow declaration order, and a store
 * built against an older copy must keep reaching the same methods.
 */
interface IStoreInstaller {
    /** Base interface version, always 1 (OverDrive Store 0.7.8-od.1 requires exactly 1). */
    int apiVersion();

    /**
     * Install or update {@code packageName} from {@code apk}. The bytes must hash to
     * {@code sha256} (lowercase or uppercase hex) and the APK must declare
     * {@code packageName}. OverDrive closes {@code apk}.
     */
    oneway void install(in ParcelFileDescriptor apk, String sha256, String packageName,
            IStoreInstallCallback callback);

    /** Uninstall {@code packageName}. */
    oneway void uninstall(String packageName, IStoreInstallCallback callback);

    /** Interface level: 2 adds installMultiple. An OverDrive without this method answers 0. */
    int apiLevel();

    /**
     * Install or update {@code packageName} from a split APK set (base + config splits) in one
     * session, like {@code adb install-multiple}. {@code sha256s[i]} is the hash of
     * {@code apks[i]}. OverDrive closes every descriptor.
     */
    oneway void installMultiple(in ParcelFileDescriptor[] apks, in String[] sha256s,
            String packageName, IStoreInstallCallback callback);
}
