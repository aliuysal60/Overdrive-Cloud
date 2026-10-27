// GatewayAssertionTest.java — the car accepts exactly what the gateway's reference verifier accepts
package com.overdrive.app.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Uses the gateway's test vector (copied to app/src/test/resources/gateway/assertion-v1.json),
 * produced by the gateway's assertion.js with a fixed test-only key: same bytes on both sides.
 */
public class GatewayAssertionTest {

    private static JSONObject vector() throws Exception {
        return new JSONObject(read("app/src/test/resources/gateway/assertion-v1.json"));
    }

    @Test
    public void acceptsTheGatewayVectorForItsCar() throws Exception {
        JSONObject v = vector();
        GatewayAssertion.Claims c = GatewayAssertion.verify(
                v.getString("token"), v.getString("publicKey"), v.getString("car"), v.getLong("nowMs"));
        assertNotNull(c);
        assertEquals(v.getString("car"), c.car);
        assertEquals("link-test-1", c.link);
    }

    @Test
    public void rejectsTamperedTokensOtherCarsAndForeignKeys() throws Exception {
        JSONObject v = vector();
        String token = v.getString("token");
        String key = v.getString("publicKey");
        String car = v.getString("car");
        long now = v.getLong("nowMs");
        assertNull(GatewayAssertion.verify(v.getString("tamperedToken"), key, car, now));
        assertNull("another car", GatewayAssertion.verify(token, key, "zzzzzzzzzzzz", now));
        // payload swapped for one claiming another car, original signature kept
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"v\":1,\"car\":\"zzzzzzzzzzzz\",\"sub\":\"x\",\"iat\":1767225600,\"exp\":1767225720}"
                        .getBytes(StandardCharsets.UTF_8));
        assertNull(GatewayAssertion.verify(forgedPayload + token.substring(token.indexOf('.')), key, "zzzzzzzzzzzz", now));
        String otherKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        assertNull("foreign key", GatewayAssertion.verify(token, otherKey, car, now));
        assertNull(GatewayAssertion.verify("garbage", key, car, now));
        assertNull(GatewayAssertion.verify(null, key, car, now));
        assertNull(GatewayAssertion.verify(token + ".extra", key, car, now));
    }

    @Test
    public void enforcesTheValidityWindowWithSkew() throws Exception {
        JSONObject v = vector();
        String token = v.getString("token");
        String key = v.getString("publicKey");
        String car = v.getString("car");
        long now = v.getLong("nowMs"); // iat; exp = iat + 120 s; the car allows 300 s of clock drift
        assertNotNull(GatewayAssertion.verify(token, key, car, now + 419_000)); // exp + 299 s
        assertNull(GatewayAssertion.verify(token, key, car, now + 421_000));    // exp + 301 s
        assertNotNull(GatewayAssertion.verify(token, key, car, now - 299_000));
        assertNull(GatewayAssertion.verify(token, key, car, now - 301_000));
    }

    @Test
    public void httpServerAcceptsAssertionsOnlyThroughTheLocalTunnel() throws Exception {
        String server = read("app/src/main/java/com/overdrive/app/server/HttpServer.java");
        // the header alone is never enough: loopback (frpc) AND tunnel headers are required
        org.junit.Assert.assertTrue(server.contains("boolean gatewayAuthenticated = gatewayAssertionHeader != null && hasTunnelHeaders"));
        org.junit.Assert.assertTrue(server.contains("&& client.getInetAddress().isLoopbackAddress()"));
        org.junit.Assert.assertTrue(server.contains("GatewayAssertion.verifyForThisCar(gatewayAssertionHeader) != null"));
        // tunnel-relayed callers (all gateway traffic) can never arm shell access
        org.junit.Assert.assertTrue(server.contains("if (c.tunnelRelayed) return false;"));
    }

    private static String read(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
