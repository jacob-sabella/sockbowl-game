package com.soulsoftworks.sockbowlgame.service;

import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
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
 *
 * <p>A second, global lock keeps join-code searches apart from saves. A
 * session is found by its join code through a RediSearch query, and the Redis
 * query engine can return no result for a document that is being rewritten
 * (JSON.SET plus EXPIRE) at the same moment: a probe against Redis 8 lost
 * about one search in ten to a concurrent save of the same session. A join
 * that lands while that session is being saved then fails with a spurious
 * 404 "Game not found" (seen in full-match). Saves hold this lock shared, so
 * they still run in parallel with each other; a join-code search holds it
 * exclusively, so no save from this process overlaps it. Searches happen only
 * on join and create, and a save never waits on a session lock while holding
 * it, so it cannot deadlock with the per-session locks.
 *
 * <p>A join-code search must not run while a session lock is held (R3-G-LOCK):
 * it would keep every save in the process waiting for as long as that session
 * is locked. The join paths search once, outside the lock, and re-read the
 * session by id inside it.
 */
public final class GameSessionLocks {

    private static final int STRIPES = 256;

    private static final ReentrantLock[] LOCKS = new ReentrantLock[STRIPES];

    static {
        for (int i = 0; i < STRIPES; i++) {
            LOCKS[i] = new ReentrantLock();
        }
    }

    private static final ReentrantReadWriteLock SEARCH_VS_SAVE = new ReentrantReadWriteLock();

    private GameSessionLocks() {
    }

    /** Run a session save; saves share the lock, so they only exclude searches. */
    public static void duringSave(Runnable save) {
        SEARCH_VS_SAVE.readLock().lock();
        try {
            save.run();
        } finally {
            SEARCH_VS_SAVE.readLock().unlock();
        }
    }

    /** Run a join-code search with no session save from this process in flight. */
    public static <T> T duringSearch(Supplier<T> search) {
        SEARCH_VS_SAVE.writeLock().lock();
        try {
            return search.get();
        } finally {
            SEARCH_VS_SAVE.writeLock().unlock();
        }
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
