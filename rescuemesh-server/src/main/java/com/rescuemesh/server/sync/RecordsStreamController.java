package com.rescuemesh.server.sync;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code GET /records/stream} — the dashboard's live feed (Option A).
 *
 * <p>Authenticated by the default rule in SecurityConfig (no change needed:
 * {@code anyRequest().authenticated()} already covers this path). Sends a
 * {@code connected} event immediately so the dashboard knows the stream is
 * live before the first record arrives.
 *
 * <p>Dashboard caveat — do NOT use the browser {@code EventSource} here: it
 * cannot send an {@code Authorization} header and would get a 401. Use the
 * fetch-based reader in the dashboard snippet below.
 */
@RestController
public class RecordsStreamController {

    private final RecordsChangedPublisher publisher;

    public RecordsStreamController(RecordsChangedPublisher publisher) {
        this.publisher = publisher;
    }

    @GetMapping(value = "/records/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = publisher.subscribe();
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data("{\"type\":\"connected\",\"serverTime\":" + System.currentTimeMillis() + "}"));
        } catch (Exception e) {
            // Subscriber already gone; the publisher's error callback prunes it.
        }
        return emitter;
    }
}
