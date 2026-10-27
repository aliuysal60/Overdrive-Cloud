// CloudConfig.java — OverDrive Cloud enrollment (car id, secret, gateway key) and the frpc.toml it implies
package com.overdrive.app.frp;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.regex.Pattern;

/**
 * What the gateway handed this car at enrollment, validated strictly (the values end up quoted
 * inside frpc.toml, so nothing may break out of its string), plus the frpc settings and the
 * non-secret summary derived from it. Persisted as {@link FrpPaths#CLOUD} (0600) by the daemon.
 *
 * <p>The car authenticates to frps with its own id + secret (frpc metadatas); the gateway's frps
 * plugin only admits an http proxy whose name and subdomain are this car id.
 */
public final class CloudConfig {
    /** Where new cars enroll; also the site users open (pairing links point here). */
    public static final String DEFAULT_GATEWAY = "https://byd-gw.cloud.solucharger.com";

    private static final Pattern CAR_ID = Pattern.compile("^[a-z2-9]{12}$");
    private static final Pattern SECRET = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final Pattern HOST = Pattern.compile(
            "^(?=.{1,253}$)([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$");
    private static final Pattern ORIGIN = Pattern.compile("^https://[A-Za-z0-9.-]{1,253}(:\\d{1,5})?$");
    private static final Pattern PAIR_CODE = Pattern.compile("^[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}$");
    // the gateway's public origin may move (it serves several names), so only the shape is fixed
    private static final Pattern PAIR_URL = Pattern.compile("^https://[A-Za-z0-9.-]{1,253}(:\\d{1,5})?/gw/#([A-Z2-9]{16})$");

    public final String gateway;
    public final String carId;
    public final String carSecret;
    /** Raw 32-byte Ed25519 public key (base64url) that signs access assertions. */
    public final String gatewayKey;
    public final String serverAddr;
    public final int serverPort;
    public final String protocol;
    public final String pairCode;
    public final String pairUrl;

    private CloudConfig(String gateway, String carId, String carSecret, String gatewayKey, String serverAddr,
                        int serverPort, String protocol, String pairCode, String pairUrl) {
        this.gateway = gateway;
        this.carId = carId;
        this.carSecret = carSecret;
        this.gatewayKey = gatewayKey;
        this.serverAddr = serverAddr;
        this.serverPort = serverPort;
        this.protocol = protocol;
        this.pairCode = pairCode;
        this.pairUrl = pairUrl;
    }

    /** From the gateway's POST /gw/api/car/enroll reply. Throws IllegalArgumentException naming the bad field. */
    public static CloudConfig fromEnrollResponse(String gateway, JSONObject r) {
        JSONObject frp = r.optJSONObject("frp");
        JSONObject key = r.optJSONObject("gatewayKey");
        if (frp == null) throw new IllegalArgumentException("missing frp settings");
        if (key == null || !"Ed25519".equals(key.optString("alg"))) throw new IllegalArgumentException("missing gateway key");
        CloudConfig c = new CloudConfig(gateway, r.optString("carId"), r.optString("carSecret"), key.optString("publicKey"),
                frp.optString("serverAddr"), frp.optInt("serverPort", -1), frp.optString("protocol"),
                r.optString("pairCode"), r.optString("pairUrl"));
        if (!c.carId.equals(frp.optString("subdomain"))) throw new IllegalArgumentException("subdomain is not the car id");
        return c.validate();
    }

    /** From the persisted cloud.json. */
    public static CloudConfig fromJson(JSONObject j) {
        return new CloudConfig(j.optString("gateway"), j.optString("carId"), j.optString("carSecret"),
                j.optString("gatewayKey"), j.optString("serverAddr"), j.optInt("serverPort", -1),
                j.optString("protocol"), j.optString("pairCode"), j.optString("pairUrl")).validate();
    }

    /** Same enrollment with a new pairing code (after the car rotated it). */
    public CloudConfig withPairing(String code, String url) {
        return new CloudConfig(gateway, carId, carSecret, gatewayKey, serverAddr, serverPort, protocol, code, url)
                .validate();
    }

    private CloudConfig validate() {
        if (!ORIGIN.matcher(gateway).matches()) throw new IllegalArgumentException("invalid gateway");
        if (!CAR_ID.matcher(carId).matches()) throw new IllegalArgumentException("invalid car id");
        if (!SECRET.matcher(carSecret).matches()) throw new IllegalArgumentException("invalid car secret");
        if (!KEY.matcher(gatewayKey).matches()) throw new IllegalArgumentException("invalid gateway key");
        if (!HOST.matcher(serverAddr).matches()) throw new IllegalArgumentException("invalid server address");
        if (serverPort < 1 || serverPort > 65535) throw new IllegalArgumentException("invalid server port");
        if (!"wss".equals(protocol) && !"tcp".equals(protocol)) throw new IllegalArgumentException("invalid protocol");
        if (!PAIR_CODE.matcher(pairCode).matches()) throw new IllegalArgumentException("invalid pairing code");
        java.util.regex.Matcher url = PAIR_URL.matcher(pairUrl);
        if (!url.matches() || !url.group(2).equals(pairCode.replace("-", ""))) {
            throw new IllegalArgumentException("invalid pairing url");
        }
        return this;
    }

    /** Persisted form (holds the secret: store it 0600). */
    public JSONObject toJson() {
        try {
            return new JSONObject()
                    .put("gateway", gateway)
                    .put("carId", carId)
                    .put("carSecret", carSecret)
                    .put("gatewayKey", gatewayKey)
                    .put("serverAddr", serverAddr)
                    .put("serverPort", serverPort)
                    .put("protocol", protocol)
                    .put("pairCode", pairCode)
                    .put("pairUrl", pairUrl);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The frpc.toml for cloud mode. Holds the car secret: store it 0600. */
    public String toToml() {
        return toToml(com.overdrive.app.BuildConfig.VERSION_NAME, com.overdrive.app.BuildConfig.VERSION_CODE);
    }

    /**
     * frpc.toml reporting the given app version: frps hands the login metadatas to the gateway,
     * which shows "update available" for the car when the release server has a newer build.
     */
    String toToml(String appVersion, long appVersionCode) {
        String version = String.valueOf(appVersion).replaceAll("[^0-9.]", "");
        return "# Generated by OverDrive (cloud mode); managed by the app.\n"
                + "serverAddr = \"" + serverAddr + "\"\n"
                + "serverPort = " + serverPort + "\n"
                + "transport.protocol = \"" + protocol + "\"\n"
                // keeps the gateway's last-seen fresh (frpc skips heartbeats under tcpMux otherwise)
                + "transport.heartbeatInterval = 30\n"
                + "transport.heartbeatTimeout = 90\n"
                + "loginFailExit = false\n"
                + "log.to = \"console\"\n"
                + "log.level = \"info\"\n"
                + "metadatas.carId = \"" + carId + "\"\n"
                + "metadatas.carSecret = \"" + carSecret + "\"\n"
                + "metadatas.appVersion = \"" + version + "\"\n"
                + "metadatas.appVersionCode = \"" + appVersionCode + "\"\n"
                + "\n"
                + "[[proxies]]\n"
                // frps proxy names are global: the gateway requires name = subdomain = car id
                + "name = \"" + carId + "\"\n"
                + "type = \"http\"\n"
                + "localIP = \"127.0.0.1\"\n"
                + "localPort = " + FrpPaths.LOCAL_PORT + "\n"
                + "subdomain = \"" + carId + "\"\n"
                // frps rewrites X-Forwarded-Proto; set it back so every relayed request counts as remote
                + "requestHeaders.set.x-forwarded-proto = \"https\"\n";
    }

    /** Non-secret summary for the app (same file the manual mode writes, plus mode/pairing). */
    public JSONObject summaryJson() {
        try {
            return new JSONObject()
                    .put("mode", "cloud")
                    .put("serverAddr", serverAddr)
                    .put("serverPort", serverPort)
                    .put("domain", gateway.substring("https://".length()))
                    .put("url", gateway + "/gw/")
                    .put("transport", protocol)
                    .put("carId", carId)
                    .put("pairCode", pairCode)
                    .put("pairUrl", pairUrl);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }
}
