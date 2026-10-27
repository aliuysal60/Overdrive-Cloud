// GatewayAssertion.java — verifies the OverDrive Cloud gateway's signed "this request may use car X" header
package com.overdrive.app.auth;

import com.google.crypto.tink.subtle.Ed25519Verify;
import com.overdrive.app.frp.CloudConfig;
import com.overdrive.app.frp.FrpStore;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The gateway adds {@code X-OD-Gateway-Assertion} to every request it relays for a signed-in
 * account linked to this car (and strips any client-sent one). Format, mirrored from the gateway's
 * src/assertion.js: {@code base64url(JSON) "." base64url(Ed25519 signature)}, signed over
 * {@code "od-gw-assert-v1." + payloadB64}; payload {@code {v:1, car, sub, usr, eml, iat, exp}}.
 *
 * <p>The public key is the one pinned at enrollment (cloud.json). Callers must additionally
 * require that the request arrived through the local frpc (loopback + tunnel headers).
 */
public final class GatewayAssertion {
    private static final String CONTEXT = "od-gw-assert-v1.";
    // generous on purpose: a head unit whose clock drifted must not lock its owner out, and an
    // assertion is only honoured through the local frpc anyway
    private static final long SKEW_SECONDS = 300;
    private static final long CACHE_MS = 30_000;

    private static volatile CloudConfig cachedCloud;
    private static volatile long cachedAt;

    private GatewayAssertion() {}

    /** The verified claims of an assertion. */
    public static final class Claims {
        public final String car;
        public final String link;
        public final String user;
        public final String email;

        Claims(String car, String link, String user, String email) {
            this.car = car;
            this.link = link;
            this.user = user;
            this.email = email;
        }
    }

    /**
     * Claims when `token` is a valid assertion for `carId`, signed by `publicKeyB64` (raw 32-byte
     * Ed25519 key, base64url) and current at `nowMs`; null for anything else.
     */
    public static Claims verify(String token, String publicKeyB64, String carId, long nowMs) {
        if (token == null || publicKeyB64 == null || carId == null) return null;
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.') || token.length() > 4096) return null;
        String payload = token.substring(0, dot);
        try {
            byte[] key = Base64.getUrlDecoder().decode(publicKeyB64);
            byte[] sig = Base64.getUrlDecoder().decode(token.substring(dot + 1));
            new Ed25519Verify(key).verify(sig, (CONTEXT + payload).getBytes(StandardCharsets.US_ASCII));
            JSONObject c = new JSONObject(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8));
            long now = nowMs / 1000;
            if (c.optInt("v") != 1 || !carId.equals(c.optString("car"))) return null;
            if (!c.has("iat") || !c.has("exp")) return null;
            if (now > c.getLong("exp") + SKEW_SECONDS || now < c.getLong("iat") - SKEW_SECONDS) return null;
            return new Claims(c.optString("car"), c.optString("sub"), c.optString("usr"), c.optString("eml"));
        } catch (Exception e) {
            // bad base64, bad signature (GeneralSecurityException), malformed JSON
            return null;
        }
    }

    /** Verifies against this car's own enrollment; null when not enrolled or invalid. */
    public static Claims verifyForThisCar(String token) {
        if (token == null || token.isEmpty()) return null;
        CloudConfig cloud = cloud();
        if (cloud == null) return null;
        return verify(token, cloud.gatewayKey, cloud.carId, System.currentTimeMillis());
    }

    /** cloud.json, re-read at most every 30 s (it changes only on enrollment / code renewal). */
    private static CloudConfig cloud() {
        long now = System.currentTimeMillis();
        if (now - cachedAt > CACHE_MS) {
            cachedCloud = FrpStore.readCloud();
            cachedAt = now;
        }
        return cachedCloud;
    }
}
