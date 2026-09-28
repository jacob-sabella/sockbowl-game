package com.soulsoftworks.sockbowlgame.usage;

import com.soulsoftworks.sockbowlgame.quota.QuotaProperties;
import com.soulsoftworks.sockbowlgame.quota.QuotaService;
import com.soulsoftworks.sockbowlgame.ratelimit.LimitSubject;
import com.soulsoftworks.sockbowlgame.ratelimit.RateLimitRedis;
import com.soulsoftworks.sockbowlgame.ratelimit.Tier;
import com.soulsoftworks.sockbowlgame.ratelimit.UsageKeys;
import com.soulsoftworks.sockbowlgame.repository.GameSessionRepository;
import com.soulsoftworks.sockbowlgame.util.MutableClock;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP-FIX3-G item 2 (G-M4-FIX3-02): {@link HostedSessionQuota#recordCreated}
 * must {@code ZADD} the real session before it {@code ZREM}s the reservation
 * token, so the ZSET's member count never briefly dips below what a
 * concurrent {@link HostedSessionQuota#reserve} should see (the "swap
 * window").
 */
class HostedSessionQuotaTest {

    private static final String OWNER_KEY = UsageKeys.userPart("alice");
    private static final String SESSIONS_KEY = UsageKeys.sessions(OWNER_KEY);

    private RedisCommands<String, String> sync;
    private HostedSessionQuota quota;

    @BeforeEach
    void setUp() {
        QuotaService quotaService = mock(QuotaService.class);
        when(quotaService.isEnabled()).thenReturn(true);
        QuotaProperties properties = new QuotaProperties();
        GameSessionRepository repository = mock(GameSessionRepository.class);
        RateLimitRedis redis = mock(RateLimitRedis.class);
        sync = mock(RedisCommands.class);
        when(redis.sync()).thenReturn(sync);
        quota = new HostedSessionQuota(quotaService, properties, repository, redis, MutableClock.startingNow());
    }

    @Test
    void addsTheRealSessionBeforeRemovingTheReservationToken() {
        HostedSessionQuota.Reservation reservation = new HostedSessionQuota.Reservation(OWNER_KEY, "rsv:abc-123");
        LimitSubject subject = new LimitSubject("alice", "198.51.100.1", Tier.PLAYER);

        quota.recordCreated(subject, "game-session-1", reservation);

        InOrder order = inOrder(sync);
        order.verify(sync).zadd(eq(SESSIONS_KEY), anyDouble(), eq("game-session-1"));
        order.verify(sync).zrem(SESSIONS_KEY, "rsv:abc-123");
    }

    @Test
    void aNullReservationTokenNeverCallsZrem() {
        LimitSubject subject = new LimitSubject("alice", "198.51.100.1", Tier.PLAYER);

        quota.recordCreated(subject, "game-session-1", HostedSessionQuota.Reservation.NONE);

        verify(sync).zadd(eq(SESSIONS_KEY), anyDouble(), eq("game-session-1"));
        verify(sync, never()).zrem(anyString(), anyString());
    }
}
