// CloudPushSinkTest.java — event → gateway request mapping, and the sink's wiring/guards in the daemon
package com.overdrive.app.notifications.sinks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.notifications.NotificationEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.json.JSONObject;
import org.junit.Test;

public class CloudPushSinkTest {

    @Test
    public void mapsAnEventToTheGatewayRequest() throws Exception {
        NotificationEvent e = new NotificationEvent("surveillance.motion", NotificationEvent.Severity.CRITICAL,
                "Hareket algılandı", "Ön kamera", "tag-1", "/events?id=7", null);
        JSONObject r = CloudPushSink.toRequest(e, null);
        assertEquals("surveillance.motion", r.getString("category"));
        assertEquals("Hareket algılandı", r.getString("title"));
        assertEquals("Ön kamera", r.getString("body"));
        assertEquals("/events?id=7", r.getString("path"));
        assertTrue(r.getBoolean("urgent"));
    }

    @Test
    public void withoutAClickUrlItFallsBackToTheHomePage() throws Exception {
        NotificationEvent e = new NotificationEvent("vehicle.door", NotificationEvent.Severity.WARN,
                "Kapı açık", null, null, null, null);
        JSONObject r = CloudPushSink.toRequest(e, null);
        assertEquals("/", r.getString("path"));
        assertEquals("", r.getString("body"));
        assertFalse(r.getBoolean("urgent"));
    }

    @Test
    public void theDaemonWiresItAndTheSinkKeepsPushSinksSourceRules() throws Exception {
        String daemon = read("app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        assertTrue(daemon.contains("new com.overdrive.app.notifications.sinks.CloudPushSink(registry)"));
        String sink = read("app/src/main/java/com/overdrive/app/notifications/sinks/CloudPushSink.java");
        assertTrue(sink.contains("if (event.isPushSuppressed()) return;"));
        assertTrue(sink.contains("if (event.getPushTargetId() != null) return;"));
        assertTrue(sink.contains("RemoteAccessNotifications.canDeliver(event, \"push\")"));
        assertTrue(sink.contains("if (FrpStore.readCloud() == null) return;"));
    }

    private static String read(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
