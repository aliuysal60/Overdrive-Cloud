// FrpConfigTest.java — validation, TOML rendering and log-state rules of the self-hosted FRP tunnel
package com.overdrive.app.frp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

public class FrpConfigTest {

    private static final String TOKEN = "abcDEF123456-_.~+/=";

    @Test
    public void normalizesUrlsToBareHosts() {
        FrpConfig c = FrpConfig.of(" https://Captain.Example.com/path ", 443, TOKEN,
                "http://BYD.example.com/");
        assertEquals("captain.example.com", c.serverAddr);
        assertEquals("byd.example.com", c.domain);
        assertEquals("https://byd.example.com", c.publicUrl());
    }

    @Test
    public void emptyDomainFallsBackToTheServer() {
        FrpConfig c = FrpConfig.of("byd.example.com", 443, TOKEN, "");
        assertEquals("byd.example.com", c.domain);
    }

    @Test
    public void port443UsesWebsocketOverTlsOtherwiseTcp() {
        assertTrue(FrpConfig.of("a.example", 443, TOKEN, null).toToml()
                .contains("transport.protocol = \"wss\"\n"));
        String tcp = FrpConfig.of("a.example", 7000, TOKEN, null).toToml();
        assertTrue(tcp.contains("transport.protocol = \"tcp\"\n"));
        assertTrue(tcp.contains("serverPort = 7000\n"));
    }

    @Test
    public void tomlPublishesOnlyTheDashboardAsAnHttpVhost() {
        String toml = FrpConfig.of("frp.example", 443, TOKEN, "car.example").toToml();
        assertTrue(toml.contains("auth.token = \"" + TOKEN + "\"\n"));
        assertTrue(toml.contains("type = \"http\"\n"));
        assertTrue(toml.contains("localIP = \"127.0.0.1\"\n"));
        assertTrue(toml.contains("localPort = 8080\n"));
        assertTrue(toml.contains("customDomains = [\"car.example\"]\n"));
        assertTrue(toml.contains("loginFailExit = false\n"));
        // relayed requests must look remote to AuthMiddleware (login required)
        assertTrue(toml.contains("requestHeaders.set.x-forwarded-proto = \"https\"\n"));
    }

    @Test
    public void rejectsValuesThatCouldBreakOutOfTheToml() {
        assertInvalid("evil\".example", 443, TOKEN, null, "invalid server address");
        assertInvalid("a.example", 443, "short", null, "invalid token");
        assertInvalid("a.example", 443, "token\"\nx = 1", null, "invalid token");
        assertInvalid("a.example", 443, TOKEN, "bad domain", "invalid domain");
        assertInvalid("a.example", 0, TOKEN, null, "invalid server port");
        assertInvalid("a.example", 65536, TOKEN, null, "invalid server port");
        assertInvalid("", 443, TOKEN, null, "invalid server address");
    }

    @Test
    public void errorsNeverEchoTheToken() {
        try {
            FrpConfig.of("a.example", 443, "secret value with spaces", null);
            fail();
        } catch (IllegalArgumentException e) {
            assertFalse(e.getMessage().contains("secret"));
        }
    }

    @Test
    public void summaryCarriesNoToken() throws Exception {
        JSONObject s = FrpConfig.of("frp.example", 443, TOKEN, "car.example").summaryJson();
        assertEquals("https://car.example", s.getString("url"));
        assertEquals("wss", s.getString("transport"));
        assertFalse(s.toString().contains(TOKEN));
        assertFalse(s.has("token"));
    }

    @Test
    public void connectionStateFollowsTheLatestLogEvent() {
        assertEquals("connecting", FrpStore.connectionStateOf(null));
        assertEquals("connecting", FrpStore.connectionStateOf("[I] try to connect to server...\n"));
        assertEquals("connected", FrpStore.connectionStateOf(
                "[I] login to server success\n[I] [overdrive] start proxy success\n"));
        assertEquals("error", FrpStore.connectionStateOf(
                "[W] login to server failed: authorization failed\n"));
        // reconnected after an outage
        assertEquals("connected", FrpStore.connectionStateOf(
                "[W] connect to server error: timeout\n[I] [overdrive] start proxy success\n"));
        // dropped after being up
        assertEquals("error", FrpStore.connectionStateOf(
                "[I] [overdrive] start proxy success\n[W] [overdrive] start error: router config conflict\n"));
    }

    private static void assertInvalid(String server, int port, String token, String domain,
            String message) {
        try {
            FrpConfig.of(server, port, token, domain);
            fail("accepted " + server + " / " + domain);
        } catch (IllegalArgumentException e) {
            assertEquals(message, e.getMessage());
        }
    }
}
