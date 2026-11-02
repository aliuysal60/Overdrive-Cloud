// IStoreInstaller.aidl — OverDrive Store's silent install/uninstall entry into OverDrive
package com.overdrive.app.store;

import com.overdrive.app.store.IStoreInstallCallback;

/**
 * Bound by OverDrive Store (com.overdrive.store) only: the service requires the
 * signature permission com.overdrive.app.permission.STORE_INSTALL. OverDrive
 * installs through its ADB shell (uid 2000), like its own updates, so the user
 * never sees an install prompt. Every call answers exactly once on the callback.
 */
interface IStoreInstaller {
    /** Interface version; the store checks it before calling. */
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
}
