package org.springframework.web.servlet.mvc.method.annotation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.http.MediaType;

/**
 * Test-only stub of {@link ResponseBodyEmitter.Handler} — the SPI the servlet
 * container normally supplies when Spring MVC attaches an emitter to a request.
 *
 * <p>This class deliberately lives in Spring's own package: both {@code Handler}
 * and {@code ResponseBodyEmitter#initialize(Handler)} are package-private, so a
 * hand-written stub can only implement/attach them from here. It is test
 * classpath only and never ships in the application jar.
 *
 * <p>Behaviour mirrors the container closely enough for publisher tests:
 * <ul>
 *   <li>{@code initialize} registers the emitter's own timeout/error/completion
 *       callbacks with this handler; they are stored here.</li>
 *   <li>{@link #complete()} runs the stored completion callbacks — what the
 *       container does when the request completes, and what makes the
 *       publisher's {@code onCompletion} pruning fire.</li>
 *   <li>With {@code failOnSend}, every {@code send} throws {@link IOException},
 *       simulating a dashboard connection that is already dead.</li>
 * </ul>
 */
public final class StubEmitterHandler implements ResponseBodyEmitter.Handler {

    private final boolean failOnSend;
    private final List<Runnable> completionCallbacks = new ArrayList<>();
    private final List<Consumer<Throwable>> errorCallbacks = new ArrayList<>();

    private StubEmitterHandler(boolean failOnSend) {
        this.failOnSend = failOnSend;
    }

    /**
     * Attach a stub handler to the emitter, as Spring MVC would when the
     * emitter is returned from a controller.
     *
     * @param failOnSend if true, every send throws {@link IOException}
     *     (a dead connection); if false, sends succeed silently
     */
    public static StubEmitterHandler attach(ResponseBodyEmitter emitter, boolean failOnSend)
            throws IOException {
        StubEmitterHandler handler = new StubEmitterHandler(failOnSend);
        emitter.initialize(handler);
        return handler;
    }

    @Override
    public void send(Object data, MediaType mediaType) throws IOException {
        if (failOnSend) {
            throw new IOException("stub handler: connection is dead");
        }
    }

    @Override
    public void send(Set<ResponseBodyEmitter.DataWithMediaType> items) throws IOException {
        if (failOnSend) {
            throw new IOException("stub handler: connection is dead");
        }
    }

    @Override
    public void complete() {
        // The container completing the request runs the emitter's completion
        // callbacks; that is what prunes the subscriber in the publisher.
        completionCallbacks.forEach(Runnable::run);
    }

    @Override
    public void completeWithError(Throwable failure) {
        errorCallbacks.forEach(callback -> callback.accept(failure));
        completionCallbacks.forEach(Runnable::run);
    }

    @Override
    public void onTimeout(Runnable callback) {
        // Stored nowhere: none of the publisher tests drive a timeout.
    }

    @Override
    public void onError(Consumer<Throwable> callback) {
        errorCallbacks.add(callback);
    }

    @Override
    public void onCompletion(Runnable callback) {
        completionCallbacks.add(callback);
    }
}
