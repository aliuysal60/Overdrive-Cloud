// FrpApiHandler.java — FRP tunnel settings API (daemon side; the token is write-only)
package com.overdrive.app.server;

import com.overdrive.app.frp.FrpConfig;
import com.overdrive.app.frp.FrpStore;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.OutputStream;

/**
 * Self-hosted FRP tunnel settings. Runs in the daemon process, which owns the config directory.
 * No response ever contains the token.
 *
 * Endpoints (JWT required, also over loopback — see HttpServer):
 * - GET    /api/frp         → {configured, summary, running, state}
 * - POST   /api/frp/config  → {"serverAddr", "serverPort", "token", "domain"}; an empty token keeps
 *                             the stored one
 * - DELETE /api/frp/config  → remove the stored settings
 * - POST   /api/frp/cloud/enroll    → leave own frps (if any) and enroll with OverDrive Cloud
 * - POST   /api/frp/cloud/pair-code → new OverDrive Cloud pairing code → {pairCode, pairUrl}
 * - GET    /api/frp/cloud/links     → Google accounts linked to this car
 * - POST   /api/frp/cloud/links/revoke {"id"} → cut one account off
 *
 * Starting and stopping frpc stays with the app (Daemons screen).
 */
public class FrpApiHandler {

    private static final String TAG = "FrpApiHandler";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    /** @return true if handled */
    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        int q = path.indexOf('?');
        String p = q >= 0 ? path.substring(0, q) : path;

        if (p.equals("/api/frp") && method.equals("GET")) {
            handleGet(out);
            return true;
        }
        if (p.equals("/api/frp/config") && method.equals("POST")) {
            handleSave(out, body);
            return true;
        }
        if (p.equals("/api/frp/cloud/enroll") && method.equals("POST")) {
            try {
                com.overdrive.app.frp.CloudConfig cloud = com.overdrive.app.frp.CloudEnrollment.switchToCloud();
                HttpResponse.sendJsonNoCors(out, new JSONObject()
                        .put("success", true).put("summary", cloud.summaryJson()).toString());
            } catch (Exception e) {
                logger.warn("Switching to OverDrive Cloud failed: " + e.getMessage());
                sendError(out, 502, "could not reach OverDrive Cloud");
            }
            return true;
        }
        if (p.equals("/api/frp/cloud/pair-code") && method.equals("POST")) {
            try {
                com.overdrive.app.frp.CloudConfig cloud = com.overdrive.app.frp.CloudEnrollment.rotatePairCode();
                HttpResponse.sendJsonNoCors(out, new JSONObject()
                        .put("success", true).put("pairCode", cloud.pairCode).put("pairUrl", cloud.pairUrl).toString());
            } catch (Exception e) {
                logger.warn("Pairing code renewal failed: " + e.getMessage());
                sendError(out, 502, "could not reach OverDrive Cloud");
            }
            return true;
        }
        if (p.equals("/api/frp/cloud/links") && method.equals("GET")) {
            try {
                JSONObject links = com.overdrive.app.frp.CloudEnrollment.linkedAccounts();
                HttpResponse.sendJsonNoCors(out, links.put("success", true).toString());
            } catch (Exception e) {
                logger.warn("Linked accounts unavailable: " + e.getMessage());
                sendError(out, 502, "could not reach OverDrive Cloud");
            }
            return true;
        }
        if (p.equals("/api/frp/cloud/links/revoke") && method.equals("POST")) {
            String id;
            try {
                id = new JSONObject(body).optString("id", "");
            } catch (Exception e) {
                id = "";
            }
            if (!id.matches("^[A-Za-z0-9_-]{1,64}$")) {
                sendError(out, 400, "invalid id");
                return true;
            }
            try {
                com.overdrive.app.frp.CloudEnrollment.revokeAccount(id);
                HttpResponse.sendJsonNoCors(out, "{\"success\":true}");
            } catch (Exception e) {
                logger.warn("Removing a linked account failed: " + e.getMessage());
                sendError(out, 502, "could not reach OverDrive Cloud");
            }
            return true;
        }
        if (p.equals("/api/frp/config") && method.equals("DELETE")) {
            if (FrpStore.delete()) {
                logger.info("FRP tunnel settings deleted");
                HttpResponse.sendJsonNoCors(out, "{\"success\":true}");
            } else {
                sendError(out, 500, "could not delete the FRP settings");
            }
            return true;
        }
        return false;
    }

    private static void handleGet(OutputStream out) throws Exception {
        JSONObject summary = FrpStore.readSummary();
        boolean running = FrpStore.isRunning();
        JSONObject r = new JSONObject();
        r.put("configured", FrpStore.hasConfig());
        // "cloud" (OverDrive Cloud enrollment), "manual" (own frps) or "none"
        r.put("mode", FrpStore.readCloud() != null ? "cloud" : FrpStore.hasConfig() ? "manual" : "none");
        r.put("summary", summary != null ? summary : JSONObject.NULL);
        r.put("running", running);
        r.put("state", running ? FrpStore.connectionState() : "stopped");
        HttpResponse.sendJsonNoCors(out, r.toString());
    }

    private static void handleSave(OutputStream out, String body) throws Exception {
        FrpConfig config;
        try {
            JSONObject json = new JSONObject(body);
            String token = json.optString("token", "").trim();
            if (token.isEmpty()) {
                // keep the stored token: only the non-secret fields changed
                token = storedToken();
                if (token == null) throw new IllegalArgumentException("token required");
            }
            config = FrpConfig.of(
                    json.optString("serverAddr", ""),
                    json.optInt("serverPort", 443),
                    token,
                    json.optString("domain", ""));
        } catch (IllegalArgumentException e) {
            sendError(out, 400, e.getMessage());
            return;
        } catch (Exception e) {
            sendError(out, 400, "invalid request");
            return;
        }
        try {
            FrpStore.save(config);
            logger.info("FRP tunnel settings saved (server " + config.serverAddr + ":" + config.serverPort
                    + ", domain " + config.domain + ")");
            HttpResponse.sendJsonNoCors(out, new JSONObject()
                    .put("success", true).put("summary", config.summaryJson()).toString());
        } catch (Exception e) {
            logger.error("FRP settings save failed: " + e.getMessage());
            sendError(out, 500, "could not save the FRP settings");
        }
    }

    /** The token in the stored frpc.toml, or null. */
    private static String storedToken() {
        try {
            java.io.File f = new java.io.File(com.overdrive.app.frp.FrpPaths.CONFIG);
            if (!f.isFile() || f.length() > 16 * 1024) return null;
            for (String line : new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
                String l = line.trim();
                if (l.startsWith("auth.token = \"") && l.endsWith("\"")) {
                    return l.substring("auth.token = \"".length(), l.length() - 1);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void sendError(OutputStream out, int status, String message) throws Exception {
        HttpResponse.sendJsonNoCors(out, status, new JSONObject()
                .put("success", false).put("error", message).toString());
    }
}
