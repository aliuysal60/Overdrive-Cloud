// StoreReleasesTest.java — versionCode read from OverDrive Store release asset names
package com.overdrive.app.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class StoreReleasesTest {
    @Test
    public void versionCodeComesFromTheApkFileName() {
        assertEquals(Long.valueOf(78001L), StoreReleases.versionCodeOf("OverDriveStore-78001.apk"));
        assertEquals(Long.valueOf(7601L), StoreReleases.versionCodeOf("OverDrivePlayStore-7601.apk"));
        assertEquals(Long.valueOf(78112L), StoreReleases.versionCodeOf("OverDriveStore-0.7.8-78112.apk"));
    }

    @Test
    public void githubDigestGivesTheLowercaseSha256() {
        String hex = "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD";
        assertEquals(hex.toLowerCase(), StoreReleases.sha256Of("sha256:" + hex));
        assertNull(StoreReleases.sha256Of(null));
        assertNull(StoreReleases.sha256Of(""));
        assertNull(StoreReleases.sha256Of("sha1:" + hex.substring(0, 40)));
        assertNull(StoreReleases.sha256Of("sha256:" + hex.substring(1)));
    }

    @Test
    public void namesWithoutAVersionCodeGiveNull() {
        assertNull(StoreReleases.versionCodeOf(null));
        assertNull(StoreReleases.versionCodeOf("OverDriveStore.apk"));
        assertNull(StoreReleases.versionCodeOf("OverDriveStore-78001.apk.sha256"));
        assertNull(StoreReleases.versionCodeOf("app-release.apk"));
    }
}
