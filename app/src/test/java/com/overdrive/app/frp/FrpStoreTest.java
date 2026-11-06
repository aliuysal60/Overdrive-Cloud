// FrpStoreTest.java — the OverDrive Cloud tunnel's connection state follows frpc's latest log event
package com.overdrive.app.frp;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FrpStoreTest {

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
}
