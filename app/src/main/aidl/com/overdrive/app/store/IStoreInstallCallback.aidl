// IStoreInstallCallback.aidl — result of one IStoreInstaller call
package com.overdrive.app.store;

interface IStoreInstallCallback {
    /**
     * {@code success} is true only when pm reported Success. {@code message} is
     * "Success" or the failure reason (e.g. "INSTALL_FAILED_UPDATE_INCOMPATIBLE").
     */
    oneway void onResult(boolean success, String message);
}
