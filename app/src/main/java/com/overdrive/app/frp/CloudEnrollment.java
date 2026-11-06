// CloudEnrollment.java — enrolls this car with the OverDrive Cloud gateway and talks to its car API
package com.overdrive.app.frp;

import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Runs in the camera daemon (uid 2000, owner of the scratch root). On first start the car enrolls
 * itself with the gateway — no account or invite needed — and stores the result via
 * {@link FrpStore#saveCloud}; frpc then connects with that identity. OverDrive Cloud is the only
 * tunnel: settings left by the removed self-hosted frps mode are deleted and the car enrolls.
 */
public final class CloudEnrollment {
    private static final DaemonLogger logger = DaemonLogger.getInstance("CloudEnrollment");
    private static final int TIMEOUT_MS = 15_000;
    private static volatile boolean started;

    private CloudEnrollment() {}

    /** Enrolls in the background, retrying with backoff while the network is down. */
    public static void startInBackground() {
        if (started) return;
        started = true;
        new Thread(() -> {
            long delayMs = 5_000L;
            while (true) {
                try {
                    if (ensureEnrolled()) return;
                } catch (Exception e) {
                    logger.warn("Cloud enrollment failed (will retry): " + e.getMessage());
                }
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                delayMs = Math.min(delayMs * 2, 300_000L); // 5 s → … → 5 min
            }
        }, "CloudEnroll").start();
    }

    /**
     * True when nothing is left to do: enrolled now or before. Throws on network or gateway
     * errors (the caller retries).
     */
    public static synchronized boolean ensureEnrolled() throws IOException {
        CloudConfig enrolled = FrpStore.readCloud();
        if (enrolled != null) {
            // after an app update the frpc settings must report the new version
            FrpStore.refreshCloudToml(enrolled);
            return true;
        }
        if (FrpStore.hasLegacyManualSettings()) {
            // the self-hosted frps mode is gone: its settings (with the shared frps token) go
            if (!FrpStore.delete()) throw new IOException("could not remove the own-server settings");
            logger.info("Removed the old own-server FRP settings; enrolling with OverDrive Cloud");
        }
        String gateway = CloudConfig.DEFAULT_GATEWAY;
        JSONObject reply = request("POST", gateway + "/gw/api/car/enroll", "{}", null, 201);
        CloudConfig cloud = CloudConfig.fromEnrollResponse(gateway, reply);
        FrpStore.saveCloud(cloud);
        logger.info("Enrolled with OverDrive Cloud as car " + cloud.carId);
        return true;
    }

    /**
     * Enrolls now (the FRP card, when the background loop hasn't managed yet). Throws on
     * network/gateway errors; the caller may simply retry.
     */
    public static synchronized CloudConfig switchToCloud() throws IOException {
        ensureEnrolled();
        CloudConfig cloud = FrpStore.readCloud();
        if (cloud == null) throw new IOException("enrollment did not complete");
        logger.info("Switched to OverDrive Cloud");
        return cloud;
    }

    /** Asks the gateway for a new pairing code (existing account links stay) and stores it. */
    public static synchronized CloudConfig rotatePairCode() throws IOException {
        CloudConfig cloud = FrpStore.readCloud();
        if (cloud == null) throw new IOException("not enrolled");
        JSONObject reply = request("POST", cloud.gateway + "/gw/api/car/pair-code", "{}", bearer(cloud), 200);
        CloudConfig rotated = cloud.withPairing(reply.optString("pairCode"), reply.optString("pairUrl"));
        FrpStore.saveCloud(rotated);
        logger.info("Pairing code renewed");
        return rotated;
    }

    /** Google accounts linked to this car: {links: [{id, email, name, createdAt, lastUsed}]}. */
    public static JSONObject linkedAccounts() throws IOException {
        CloudConfig cloud = FrpStore.readCloud();
        if (cloud == null) throw new IOException("not enrolled");
        return request("GET", cloud.gateway + "/gw/api/car/links", null, bearer(cloud), 200);
    }

    /** Cuts one account off this car (the gateway returns 404 for an unknown or foreign link). */
    public static void revokeAccount(String linkId) throws IOException {
        CloudConfig cloud = FrpStore.readCloud();
        if (cloud == null) throw new IOException("not enrolled");
        String body;
        try {
            body = new JSONObject().put("id", linkId).toString();
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        }
        request("POST", cloud.gateway + "/gw/api/car/links/revoke", body, bearer(cloud), 200);
        logger.info("Linked account removed");
    }

    /** Sends one notification to the gateway, which pushes it to every linked account's devices. */
    public static JSONObject notifyGateway(String body) throws IOException {
        CloudConfig cloud = FrpStore.readCloud();
        if (cloud == null) throw new IOException("not enrolled");
        return request("POST", cloud.gateway + "/gw/api/car/notify", body, bearer(cloud), 200);
    }

    /** Bearer credential of this car for the gateway's car API. */
    static String bearer(CloudConfig cloud) {
        return "Bearer " + cloud.carId + "." + cloud.carSecret;
    }

    /**
     * JSON request to the gateway; returns the parsed reply when the status is `expected`,
     * throws IOException otherwise (message carries the status, never the credential).
     */
    static JSONObject request(String method, String url, String body, String authorization, int expected)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("Accept", "application/json");
            if (authorization != null) c.setRequestProperty("Authorization", authorization);
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : readLimited(in, 64 * 1024);
            if (status != expected) throw new IOException(method + " " + new URL(url).getPath() + " → HTTP " + status);
            try {
                return new JSONObject(text.isEmpty() ? "{}" : text);
            } catch (Exception e) {
                throw new IOException("gateway reply is not JSON");
            }
        } finally {
            c.disconnect();
        }
    }

    private static String readLimited(InputStream in, int max) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = s.read(chunk)) > 0) {
                if (buf.size() + n > max) throw new IOException("gateway reply too large");
                buf.write(chunk, 0, n);
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
