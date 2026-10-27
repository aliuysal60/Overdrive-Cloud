// CloudReleasesTest.java — release server answer validation, update decision and SHA-256 check
package com.overdrive.app.updater;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

public class CloudReleasesTest {

    private static final String SHA = "7f2a00f944a4b0f7e9ccd49d38a533cc8f1e769a12ae3d02f43cac582dfa5e07";

    private static JSONObject latest() throws Exception {
        return new JSONObject()
                .put("versionName", "57.0.3").put("versionCode", 5700003).put("sha256", SHA)
                .put("size", 149446428L).put("fileName", "OverDrive-57.0.3.apk")
                .put("notes", "- Güncelleme var").put("publishedAt", "2026-10-26T16:46:21.108Z")
                .put("url", "/download/OverDrive-57.0.3.apk");
    }

    @Test
    public void parsesTheServerAnswer() throws Exception {
        CloudReleases.Release r = CloudReleases.parse(latest().toString());
        assertEquals("57.0.3", r.versionName);
        assertEquals(5700003L, r.versionCode);
        assertEquals(SHA, r.sha256);
        assertEquals(149446428L, r.size);
        assertEquals(CloudReleases.ORIGIN + "/download/OverDrive-57.0.3.apk", r.downloadUrl);
        assertEquals("- Güncelleme var", r.notes);
        assertEquals("alpha-v57.0.3", r.label("alpha"));
        assertEquals("v57.0.3", r.label(""));
    }

    @Test
    public void offersOnlyAHigherVersionCode() throws Exception {
        CloudReleases.Release r = CloudReleases.parse(latest().toString());
        assertTrue(r.isNewerThan(102));
        assertTrue(r.isNewerThan(5700002));
        assertFalse(r.isNewerThan(5700003));
        assertFalse(r.isNewerThan(5700004));
    }

    /** The version name reaches a shell script and the URL is downloaded: both must be strict. */
    @Test
    public void refusesMalformedAnswers() throws Exception {
        String[][] bad = {
                {"versionName", "57.0.1';reboot;'"},
                {"versionName", ""},
                {"sha256", "XYZ"},
                {"sha256", SHA.toUpperCase()},
                {"url", "https://evil.example/x.apk"},
                {"url", "/download/../../etc/passwd.apk"},
                {"url", "//evil.example/download/x.apk"},
        };
        for (String[] b : bad) {
            assertRefused(latest().put(b[0], b[1]).toString(), b[0] + "=" + b[1]);
        }
        assertRefused(latest().put("versionCode", 0).toString(), "versionCode 0");
        assertRefused(latest().put("size", 0).toString(), "size 0");
        assertRefused("not json", "not json");
    }

    private static void assertRefused(String json, String what) {
        try {
            CloudReleases.parse(json);
            fail("accepted " + what);
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void checksTheDownloadedDigest() {
        assertTrue(CloudReleases.digestMatches(SHA + "  /data/local/tmp/overdrive_update.apk\n", SHA));
        assertTrue(CloudReleases.digestMatches(SHA.toUpperCase(), SHA));
        assertFalse(CloudReleases.digestMatches("0" + SHA.substring(1) + "  file", SHA));
        assertFalse(CloudReleases.digestMatches("sha256sum: not found", SHA));
        assertFalse(CloudReleases.digestMatches("", SHA));
        assertFalse(CloudReleases.digestMatches(null, SHA));
    }
}
