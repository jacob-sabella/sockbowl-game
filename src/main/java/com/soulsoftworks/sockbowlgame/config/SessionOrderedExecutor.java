package com.soulsoftworks.sockbowlgame.config;

import org.springframework.core.task.TaskExecutor;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.MessageHandlingRunnable;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;

/**
 * Executor for the STOMP client inbound channel that runs each WebSocket
 * session's frames one at a time, in the order they arrived, while different
 * sessions still run in parallel on the shared pool.
 *
 * <p>Spring hands every inbound frame to a thread pool, so two frames one
 * client sends back to back (set-proctor then set-match-packet in
 * full-match) can be handled, and produced to Kafka, in either order; the
 * swapped pair refused the packet because the sender was not proctor yet.
 * Spring's own {@code preserveReceiveOrder} fixes the order but defers the
 * channel interceptors too, and then an interceptor's rejection of a CONNECT
 * no longer becomes an ERROR frame. This executor leaves the interceptors'
 * {@code preSend} where it was (on the WebSocket thread, which already sees a
 * session's frames in order) and orders only the handling that follows.
 *
 * <p>Sessions are hashed onto a fixed number of serial lanes, so memory stays
 * bounded and there is nothing to clean up when a session closes; sessions
 * that share a lane are simply serialized with each other. Tasks without a
 * session id go straight to the pool. It is a {@link TaskExecutor} because
 * Spring registers the inbound executor as the
 * {@code clientInboundChannelExecutor} bean and injects it by that type.
 */
public final class SessionOrderedExecutor implements TaskExecutor {

    private final Executor pool;
    private final Lane[] lanes;

    public SessionOrderedExecutor(Executor pool, int laneCount) {
        if (laneCount < 1) {
            throw new IllegalArgumentException("laneCount must be positive");
        }
        this.pool = pool;
        this.lanes = new Lane[laneCount];
        for (int i = 0; i < laneCount; i++) {
            lanes[i] = new Lane();
        }
    }

    @Override
    public void execute(Runnable task) {
        String sessionId = sessionIdOf(task);
        if (sessionId == null) {
            pool.execute(task);
        } else {
            lanes[Math.floorMod(sessionId.hashCode(), lanes.length)].execute(task);
        }
    }

    private static String sessionIdOf(Runnable task) {
        if (task instanceof MessageHandlingRunnable handling) {
            return SimpMessageHeaderAccessor.getSessionId(handling.getMessage().getHeaders());
        }
        return null;
    }

    /** Runs its tasks one after another, in submission order, on the pool. */
    private final class Lane {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private boolean running;

        synchronized void execute(Runnable task) {
            tasks.add(task);
            if (!running) {
                running = true;
                submitNext();
            }
        }

        /** Called with the lane's monitor held. */
        private void submitNext() {
            Runnable next = tasks.poll();
            if (next == null) {
                running = false;
                return;
            }
            try {
                pool.execute(() -> {
                    try {
                        next.run();
                    } finally {
                        synchronized (Lane.this) {
                            submitNext();
                        }
                    }
                });
            } catch (RuntimeException rejected) {
                // Pool shut down or saturated: drop this lane's backlog rather
                // than wedge it, and surface the rejection to the sender.
                tasks.clear();
                running = false;
                throw rejected;
            }
        }
    }
}
