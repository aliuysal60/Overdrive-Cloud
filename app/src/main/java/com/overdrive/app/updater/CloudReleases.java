// CloudReleases.java — OverDrive Cloud release server: where updates come from and how its answer is validated
package com.overdrive.app.updater;

import org.json.JSONObject;

import java.util.regex.Pattern;

/**
 * This build takes its updates from the OverDrive Cloud release server instead of the upstream
 * GitHub releases. {@code release.ps1} publishes every signed build there; the server answers
 * {@code GET /api/releases/latest} with the newest one:
 * <pre>{"versionName":"57.0.3","versionCode":5700003,"sha256":"…","size":149446428,
 *  "fileName":"OverDrive-57.0.3.apk","notes":"…","publishedAt":"…","url":"/download/OverDrive-57.0.3.apk"}</pre>
 * An update is offered when its versionCode is above the running build's. The download URL must
 * stay on the release server, and the APK's SHA-256 is checked before it is installed.
 */
public final class CloudReleases {

    /** Release server origin (the OverDrive website). */
    public static final String ORIGIN = "https://overdrive.cloud.solucharger.com";
    public static final String LATEST_URL = ORIGIN + "/api/releases/latest";

    /**
     * Updates come from the release server. When on, the upstream alpha "pick any version"
     * catalog is not offered: there is a single newest build, like the braveheart flow.
     */
    public static final boolean ENABLED = true;

    private static final Pattern VERSION_NAME = Pattern.compile("^\\d{1,4}\\.\\d{1,4}(\\.\\d{1,5})?$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern DOWNLOAD_PATH = Pattern.compile("^/download/[A-Za-z0-9._-]+\\.apk$");

    private CloudReleases() {}

    /** A published release as the server describes it. */
    public static final class Release {
        public final String versionName;
        public final long versionCode;
        public final String sha256;
        public final long size;
        public final String downloadUrl;
        public final String notes;
        public final String publishedAt;

        Release(String versionName, long versionCode, String sha256, long size,
                String downloadUrl, String notes, String publishedAt) {
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.sha256 = sha256;
            this.size = size;
            this.downloadUrl = downloadUrl;
            this.notes = notes;
            this.publishedAt = publishedAt;
        }

        /** Display label in the updater's canonical "<channel>-v<version>" form. */
        public String label(String channel) {
            return (channel == null || channel.isEmpty()) ? "v" + versionName : channel + "-v" + versionName;
        }

        public boolean isNewerThan(long installedVersionCode) {
            return versionCode > installedVersionCode;
        }
    }

    /**
     * Parses and validates a {@code /api/releases/latest} answer. Anything malformed is refused:
     * the version name ends up in a shell script and the URL is downloaded by the daemon.
     */
    public static Release parse(String json) {
        JSONObject o;
        try {
            o = new JSONObject(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("release server sent invalid JSON");
        }
        String versionName = o.optString("versionName", "");
        if (!VERSION_NAME.matcher(versionName).matches()) {
            throw new IllegalArgumentException("invalid versionName: " + versionName);
        }
        long versionCode = o.optLong("versionCode", 0L);
        if (versionCode <= 0) throw new IllegalArgumentException("invalid versionCode");
        String sha256 = o.optString("sha256", "");
        if (!SHA256.matcher(sha256).matches()) throw new IllegalArgumentException("invalid sha256");
        long size = o.optLong("size", 0L);
        if (size <= 0) throw new IllegalArgumentException("invalid size");
        String path = o.optString("url", "");
        if (!DOWNLOAD_PATH.matcher(path).matches()) throw new IllegalArgumentException("invalid download url: " + path);
        return new Release(versionName, versionCode, sha256, size, ORIGIN + path,
                o.optString("notes", ""), o.optString("publishedAt", ""));
    }

    /** True when {@code sha256sum} output starts with the expected digest. */
    public static boolean digestMatches(String sha256sumOutput, String expected) {
        if (sha256sumOutput == null || expected == null) return false;
        String out = sha256sumOutput.trim().toLowerCase(java.util.Locale.ROOT);
        int space = out.indexOf(' ');
        String digest = space < 0 ? out : out.substring(0, space);
        return SHA256.matcher(digest).matches() && digest.equals(expected);
    }
}
