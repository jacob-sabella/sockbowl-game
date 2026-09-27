package com.soulsoftworks.sockbowlgame.service;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Serializes every read-modify-write of a {@code GameSession} per session
 * (M2R2-LIVE-01).
 *
 * <p>A session is one Redis JSON document that each writer loads, mutates in
 * memory and saves back whole: the REST join paths in {@link SessionService},
 * the Kafka listener in {@link MessageService} and the timer tick in
 * {@link GameTimerService}. Two of those running at once for the same session
 * is a lost update: the writer that loaded first saves last and erases the
 * other one's change. The observed failure was a REST join landing while a
 * bot's on-connect get-game was being processed; the processor saved its stale
 * copy and the new player vanished, so its next message failed with
 * PLAYER_NOT_IN_SESSION.
 *
 * <p>Every writer therefore runs its load, mutation and save inside
 * {@link #withLock}. The load must happen inside the lock: a copy loaded
 * before it is stale by the time the lock is held.
 *
 * <p>The locks are in-process. That is sufficient because the game service
 * runs as a single instance: it uses Spring's in-memory simple STOMP broker,
 * so clients of one game must all be connected to the same JVM anyway.
 * Scaling out needs a broker relay and a distributed lock (or a Redis
 * WATCH/version check) in place of this class.
 *
 * <p>The locks are striped (a fixed array indexed by the session id's hash),
 * so memory stays bounded however many sessions come and go. Two sessions
 * that share a stripe are merely serialized with each other. The locks are
 * reentrant, so a writer that re-enters (for example a timer tick whose
 * auto-timeout is delivered synchronously by a test loopback) cannot
 * deadlock on its own session. A writer never takes a second session's lock
 * while holding one.
 */
public final class GameSessionLocks {

    private static final int STRIPES = 256;

    private static final ReentrantLock[] LOCKS = new ReentrantLock[STRIPES];

    static {
        for (int i = 0; i < STRIPES; i++) {
            LOCKS[i] = new ReentrantLock();
        }
    }

    private GameSessionLocks() {
    }

    private static ReentrantLock lockFor(String gameSessionId) {
        return LOCKS[Math.floorMod(gameSessionId.hashCode(), STRIPES)];
    }

    /**
     * Run {@code action} while holding the lock for {@code gameSessionId}.
     * A null id (a malformed message) has nothing to serialize against and
     * runs unlocked, so the caller's own null handling still applies.
     */
    public static <T> T withLock(String gameSessionId, Supplier<T> action) {
        if (gameSessionId == null) {
            return action.get();
        }
        ReentrantLock lock = lockFor(gameSessionId);
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /** {@link #withLock(String, Supplier)} for an action with no result. */
    public static void withLock(String gameSessionId, Runnable action) {
        withLock(gameSessionId, () -> {
            action.run();
            return null;
        });
    }

    /**
     * Whether {@code thread} is waiting for this session's lock. For tests
     * that pin an interleaving: it proves a writer is parked on the lock
     * rather than racing ahead.
     */
    static boolean isQueued(String gameSessionId, Thread thread) {
        return lockFor(gameSessionId).hasQueuedThread(thread);
    }
}
