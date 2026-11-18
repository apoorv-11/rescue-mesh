package com.rescuemesh.server.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE fan-out for the dashboard (Option A, kickoff §3).
 *
 * <p>One event, thin payload: subscribers get {@code records-changed} /
 * {@code photo-changed} with messageIds, then refetch the {@code /records/*}
 * lists. Publishing never throws to a sync/photo caller — a dead emitter is
 * pruned and the caller proceeds.
 */
@Service
public class RecordsChangedPublisher {

    private static final Logger log = LoggerFactory.getLogger(RecordsChangedPublisher.class);

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L); // no server-side timeout; heartbeat below keeps it alive
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    /** Called by SyncService after a batch commits with >= 1 ACCEPTED item. */
    public void publishRecordsChanged(List<String> acceptedMessageIds, long serverTime) {
        ObjectNode data = mapper.createObjectNode();
        data.put("type", "records-changed");
        ArrayNode ids = mapper.createArrayNode();
        acceptedMessageIds.forEach(ids::add);
        data.set("messageIds", ids);
        data.put("serverTime", serverTime);
        sendToAll("records-changed", data);
    }

    /** Called by PhotoController after a photo stores (STORED or ALREADY_EXISTS). */
    public void publishPhotoChanged(String messageId, long serverTime) {
        ObjectNode data = mapper.createObjectNode();
        data.put("type", "photo-changed");
        data.put("messageId", messageId);
        data.put("serverTime", serverTime);
        sendToAll("photo-changed", data);
    }

    private void sendToAll(String eventName, ObjectNode data) {
        String json;
        try {
            json = mapper.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("SSE payload serialisation failed for {}", eventName, e);
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(json));
            } catch (Exception e) {
                emitters.remove(emitter);
            }
        }
    }

    /** Comment heartbeat so proxies and Render do not idle-close the stream. */
    @Scheduled(fixedRate = 25_000)
    public void heartbeat() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (Exception e) {
                emitters.remove(emitter);
            }
        }
    }

    /** Test seam: live subscriber count. */
    int subscriberCount() {
        return emitters.size();
    }
}
