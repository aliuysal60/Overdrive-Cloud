// FrpApiHandler.java — FRP tunnel settings API (daemon side; the token is write-only)
package com.overdrive.app.server;

import com.overdrive.app.frp.FrpStore;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONObject;

import java.io.OutputStream;

/**
 * OverDrive Cloud tunnel state and enrollment. Runs in the daemon process, which owns the config
 * directory. No response ever contains the car's secret. (The self-hosted frps mode, with its
 * POST/DELETE /api/frp/config, was removed.)
 *
 * Endpoints (JWT required, also over loopback — see HttpServer):
 * - GET    /api/frp         → {configured, mode, summary, running, state}
 * - POST   /api/frp/cloud/enroll    → enroll with OverDrive Cloud now
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
        return false;
    }

    private static void handleGet(OutputStream out) throws Exception {
        JSONObject summary = FrpStore.readSummary();
        boolean running = FrpStore.isRunning();
        JSONObject r = new JSONObject();
        r.put("configured", FrpStore.hasConfig());
        // "cloud" (OverDrive Cloud enrollment) or "none"
        r.put("mode", FrpStore.readCloud() != null ? "cloud" : "none");
        r.put("summary", summary != null ? summary : JSONObject.NULL);
        r.put("running", running);
        r.put("state", running ? FrpStore.connectionState() : "stopped");
        HttpResponse.sendJsonNoCors(out, r.toString());
    }

    private static void sendError(OutputStream out, int status, String message) throws Exception {
        HttpResponse.sendJsonNoCors(out, status, new JSONObject()
                .put("success", false).put("error", message).toString());
    }
}
