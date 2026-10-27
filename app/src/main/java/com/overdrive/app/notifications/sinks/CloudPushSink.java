// CloudPushSink.java — forwards notifications to the OverDrive Cloud gateway, which pushes them to linked accounts
package com.overdrive.app.notifications.sinks;

import com.overdrive.app.frp.CloudEnrollment;
import com.overdrive.app.frp.FrpStore;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.notifications.CategoryRegistry;
import com.overdrive.app.notifications.NotificationBus;
import com.overdrive.app.notifications.NotificationEvent;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Cloud counterpart of {@link PushSink}. When the car is enrolled with OverDrive Cloud, each event
 * that would be pushed goes to the gateway's POST /gw/api/car/notify (car credential); the gateway
 * fans it out to every device of every linked account that has notifications on for this car.
 *
 * <p>Honours the same source-level rules as PushSink (push-suppressed events, remote-access
 * delivery rules); targeted diagnostics pushes stay local. Per-device mute/quiet hours are this
 * car's local subscriptions' settings and do not apply here — cloud users switch alerts per car.
 * Sending runs on its own bounded single thread so the bus never waits on the network.
 */
public final class CloudPushSink implements NotificationBus.Sink {

    private static final DaemonLogger logger = DaemonLogger.getInstance("CloudPushSink");

    private final CategoryRegistry registry;
    // one sender; a burst beyond 32 queued events is dropped (the gateway rate-limits cars anyway)
    private final ExecutorService executor = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(32), r -> {
                Thread t = new Thread(r, "CloudPushSink");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.DiscardPolicy());

    public CloudPushSink(CategoryRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onNotification(NotificationEvent event) {
        if (event.isPushSuppressed()) return;
        if (event.getPushTargetId() != null) return; // "send test" to one local device
        if (com.overdrive.app.remoteaccess.RemoteAccessNotifications.isRemoteAccess(event)
                && !com.overdrive.app.remoteaccess.RemoteAccessNotifications.canDeliver(event, "push")) return;
        if (FrpStore.readCloud() == null) return; // not enrolled: nothing to forward to
        JSONObject body = toRequest(event, registry);
        executor.execute(() -> {
            try {
                JSONObject r = CloudEnrollment.notifyGateway(body.toString());
                logger.debug("cloud push " + event.category + ": sent " + r.optInt("sent") + ", removed " + r.optInt("removed"));
            } catch (Exception e) {
                logger.warn("cloud push failed (" + event.category + "): " + e.getMessage());
            }
        });
    }

    /** The gateway request for an event: click path from the event or the category default. */
    static JSONObject toRequest(NotificationEvent event, CategoryRegistry registry) {
        String path = event.clickUrl;
        if (path == null) {
            CategoryRegistry.Entry meta = registry == null ? null : registry.get(event.category);
            path = meta != null ? meta.defaultClickUrl : "/";
        }
        try {
            return new JSONObject()
                    .put("category", event.category)
                    .put("title", event.title == null ? "" : event.title)
                    .put("body", event.body == null ? "" : event.body)
                    .put("path", path)
                    .put("urgent", event.severity == NotificationEvent.Severity.CRITICAL);
        } catch (org.json.JSONException e) {
            throw new IllegalStateException(e);
        }
    }
}
