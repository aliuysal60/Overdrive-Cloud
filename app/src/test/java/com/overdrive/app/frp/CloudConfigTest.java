// CloudConfigTest.java — cloud enrollment parsing, persistence round-trip, frpc.toml and summary rules
package com.overdrive.app.frp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

public class CloudConfigTest {

    private static final String GW = "https://byd-gw.cloud.solucharger.com";
    private static final String SECRET = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCdE";
    private static final String KEY = "ebVWLo_mVPlAeLES6KmLp5AfhTrmlb7X4OORC60ElmQ";

    /** The shape the gateway's POST /gw/api/car/enroll returns. */
    private static JSONObject reply() throws Exception {
        return new JSONObject()
                .put("carId", "km4ah2k7qcyh")
                .put("carSecret", SECRET)
                .put("frp", new JSONObject().put("serverAddr", "frp.cloud.solucharger.com").put("serverPort", 443)
                        .put("protocol", "wss").put("subdomain", "km4ah2k7qcyh"))
                .put("gatewayKey", new JSONObject().put("alg", "Ed25519").put("publicKey", KEY))
                .put("pairCode", "ABCD-EFGH-JKMN-PQRS")
                .put("pairUrl", GW + "/gw/#ABCDEFGHJKMNPQRS");
    }

    @Test
    public void parsesTheEnrollReplyAndRoundTripsThroughJson() throws Exception {
        CloudConfig c = CloudConfig.fromEnrollResponse(GW, reply());
        assertEquals("km4ah2k7qcyh", c.carId);
        assertEquals(KEY, c.gatewayKey);
        CloudConfig again = CloudConfig.fromJson(c.toJson());
        assertEquals(c.toToml(), again.toToml());
        assertEquals(c.pairUrl, again.pairUrl);
    }

    @Test
    public void tomlCarriesTheCarIdentityAndOnlyItsOwnVhost() throws Exception {
        String toml = CloudConfig.fromEnrollResponse(GW, reply()).toToml();
        assertTrue(toml.contains("serverAddr = \"frp.cloud.solucharger.com\"\n"));
        assertTrue(toml.contains("transport.protocol = \"wss\"\n"));
        assertTrue(toml.contains("transport.heartbeatInterval = 30\n"));
        assertTrue(toml.contains("metadatas.carId = \"km4ah2k7qcyh\"\n"));
        assertTrue(toml.contains("metadatas.carSecret = \"" + SECRET + "\"\n"));
        assertTrue(toml.contains("name = \"km4ah2k7qcyh\"\n"));
        assertTrue(toml.contains("subdomain = \"km4ah2k7qcyh\"\n"));
        assertTrue(toml.contains("localPort = 8080\n"));
        assertTrue(toml.contains("requestHeaders.set.x-forwarded-proto = \"https\"\n"));
        assertFalse("no shared token in cloud mode", toml.contains("auth.token"));
    }

    @Test
    public void tomlReportsTheAppVersionToTheGateway() throws Exception {
        CloudConfig c = CloudConfig.fromEnrollResponse(GW, reply());
        String toml = c.toToml("57.0.2", 5700002L);
        assertTrue(toml.contains("metadatas.appVersion = \"57.0.2\"\n"));
        assertTrue(toml.contains("metadatas.appVersionCode = \"5700002\"\n"));
        // a version name can never break out of the TOML string
        assertTrue(c.toToml("57.0\"\nx = \"1", 1L).contains("metadatas.appVersion = \"57.01\"\n"));
    }

    @Test
    public void summaryPointsAtTheGatewayAndCarriesNoSecret() throws Exception {
        JSONObject s = CloudConfig.fromEnrollResponse(GW, reply()).summaryJson();
        assertEquals("cloud", s.getString("mode"));
        assertEquals(GW + "/gw/", s.getString("url"));
        assertEquals("ABCD-EFGH-JKMN-PQRS", s.getString("pairCode"));
        assertFalse(s.toString().contains(SECRET));
    }

    @Test
    public void rejectsRepliesThatCouldBreakTheTomlOrPointElsewhere() throws Exception {
        String[][] bad = {
            {"carId", "evil\"id\nx=1"}, {"carId", "UPPERCASE123"}, {"carSecret", "short"},
            {"pairCode", "nope"}, {"pairUrl", "http://plain.example/gw/#ABCDEFGHJKMNPQRS"},
            {"pairUrl", "https://evil.example/gw/#WXYZWXYZWXYZWXYZ"}, {"pairUrl", "javascript:alert(1)//gw/#ABCDEFGHJKMNPQRS"},
        };
        for (String[] b : bad) {
            JSONObject r = reply().put(b[0], b[1]);
            assertRejected(r, b[0]);
        }
        JSONObject r = reply();
        r.getJSONObject("frp").put("serverAddr", "evil\".example");
        assertRejected(r, "serverAddr");
        r = reply();
        r.getJSONObject("frp").put("subdomain", "otherid22222");
        assertRejected(r, "subdomain");
        r = reply();
        r.getJSONObject("gatewayKey").put("alg", "RS256");
        assertRejected(r, "alg");
        try {
            CloudConfig.fromEnrollResponse("http://plain.example", reply());
            fail("http gateway accepted");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void pairingLinksMayMoveToTheGatewaysNewPublicName() throws Exception {
        // enrolled through byd-gw; after the cut-over the gateway hands out byd.cloud links
        CloudConfig c = CloudConfig.fromEnrollResponse(GW, reply())
                .withPairing("WXYZ-2345-6789-ABCD", "https://byd.cloud.solucharger.com/gw/#WXYZ23456789ABCD");
        assertEquals(GW, c.gateway);
        assertEquals("https://byd.cloud.solucharger.com/gw/#WXYZ23456789ABCD", c.pairUrl);
    }

    @Test
    public void rotatedPairingKeepsTheIdentity() throws Exception {
        CloudConfig c = CloudConfig.fromEnrollResponse(GW, reply()).withPairing("WXYZ-2345-6789-ABCD", GW + "/gw/#WXYZ23456789ABCD");
        assertEquals("WXYZ-2345-6789-ABCD", c.pairCode);
        assertEquals("km4ah2k7qcyh", c.carId);
    }

    private static void assertRejected(JSONObject r, String what) {
        try {
            CloudConfig.fromEnrollResponse(GW, r);
            fail("accepted bad " + what);
        } catch (IllegalArgumentException expected) {
        }
    }
}
