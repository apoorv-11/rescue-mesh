package com.rescuemesh.server.sync;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StubEmitterHandler;

/**
 * Publisher behaviour without HTTP: subscribe/complete bookkeeping, and a
 * publish to a dead emitter prunes it instead of throwing — so a dead
 * dashboard can never fail a /sync/batch or /sync/photo call.
 *
 * <p>Every emitter is first attached to a {@link StubEmitterHandler}, the way
 * the servlet container attaches it in production. A detached
 * {@code SseEmitter} has no Handler, so {@code complete()} runs no completion
 * callbacks and {@code send()} buffers instead of failing — neither pruning
 * path can fire there, which would be a test artefact, not publisher
 * behaviour.
 */
class RecordsChangedPublisherTests {

    @Test
    void subscribeThenCompleteRemovesSubscriber() throws IOException {
        RecordsChangedPublisher publisher = new RecordsChangedPublisher();
        SseEmitter emitter = publisher.subscribe();
        StubEmitterHandler.attach(emitter, false);
        assertEquals(1, publisher.subscriberCount());

        emitter.complete();

        assertEquals(0, publisher.subscriberCount());
    }

    @Test
    void publishToDeadEmitterPrunesItWithoutThrowing() throws IOException {
        RecordsChangedPublisher publisher = new RecordsChangedPublisher();
        SseEmitter emitter = publisher.subscribe();
        StubEmitterHandler.attach(emitter, true); // attached, but every send fails

        assertDoesNotThrow(() ->
                publisher.publishRecordsChanged(List.of("some-message-id"), 1700001000000L));
        assertEquals(0, publisher.subscriberCount());
    }

    @Test
    void publishPhotoChangedToDeadEmitterPrunesItWithoutThrowing() throws IOException {
        RecordsChangedPublisher publisher = new RecordsChangedPublisher();
        SseEmitter emitter = publisher.subscribe();
        StubEmitterHandler.attach(emitter, true); // attached, but every send fails

        assertDoesNotThrow(() ->
                publisher.publishPhotoChanged("some-message-id", 1700001000000L));
        assertEquals(0, publisher.subscriberCount());
    }
}
