package com.soulsoftworks.sockbowlgame.service.ban;

import com.soulsoftworks.sockbowlgame.controller.exception.UserBannedException;
import com.soulsoftworks.sockbowlgame.model.entity.BanRecord;
import com.soulsoftworks.sockbowlgame.model.security.AuthenticatedUser;
import com.soulsoftworks.sockbowlgame.ratelimit.RedisUnavailableException;
import com.soulsoftworks.sockbowlgame.ratelimit.SubjectBanChecker.SubjectBan;
import com.soulsoftworks.sockbowlgame.ratelimit.SubjectBannedException;
import com.soulsoftworks.sockbowlgame.repository.BanRepository;
import com.soulsoftworks.sockbowlgame.service.BanService;
import com.soulsoftworks.sockbowlgame.service.authorization.GameAuthorizationPolicy;
import com.soulsoftworks.sockbowlgame.util.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RL-06: the per-message ban check ({@link GameAuthorizationPolicy#ensureNotBanned})
 * goes through {@link BanStatusCache}, so repeated checks cost at most one
 * Redis (or Postgres) read per subject per TTL, and the local create/remove
 * paths invalidate it (plan m4-limits WP-G4).
 */
class BanStatusCacheTest {

    private static final Instant START = Instant.parse("2026-09-27T12:00:00Z");
    private static final Duration TTL = Duration.ofSeconds(30);

    private BanRepository repository;
    private BanRedisMirror mirror;
    private MutableClock clock;
    private BanService banService;
    private GameAuthorizationPolicy policy;

    private final AuthenticatedUser alice =
            AuthenticatedUser.of("kc-alice", "alice", "alice@example.com", List.of("game:host"));

    @BeforeEach
    void setUp() {
        repository = mock(BanRepository.class);
        mirror = mock(BanRedisMirror.class);
        clock = new MutableClock(START);
        banService = new BanService(repository, mirror, clock, TTL);
        policy = new GameAuthorizationPolicy(true, banService);
        when(mirror.read(anyString())).thenReturn(Optional.empty());
        when(repository.save(any(BanRecord.class))).thenAnswer(inv -> {
            BanRecord b = inv.getArgument(0);
            b.setId(UUID.randomUUID());
            return b;
        });
    }

    @Test
    void hundredChecksInThirtySecondsCostOneRead() {
        for (int i = 0; i < 100; i++) {
            policy.ensureNotBanned(alice);
            clock.advance(Duration.ofMillis(290)); // 100 x 290ms = 29s < 30s
        }
        verify(mirror, times(1)).read("kc-alice");
        verify(repository, never()).findActiveBans(anyString(), any());
        verify(repository, never()).findActiveBan(anyString(), any());
    }

    @Test
    void answerIsReReadAfterTheTtl() {
        policy.ensureNotBanned(alice);
        clock.advance(TTL.plusSeconds(1));
        policy.ensureNotBanned(alice);
        verify(mirror, times(2)).read("kc-alice");
    }

    @Test
    void bannedSubjectIsRejectedFromTheMirror() {
        when(mirror.read("kc-alice")).thenReturn(Optional.of(new SubjectBan("spam", null)));
        for (int i = 0; i < 100; i++) {
            assertThatThrownBy(() -> policy.ensureNotBanned(alice)).isInstanceOf(UserBannedException.class);
        }
        verify(mirror, times(1)).read("kc-alice");
    }

    @Test
    void createInvalidatesTheCachedAnswer() {
        policy.ensureNotBanned(alice); // caches "not banned"
        BanRecord ban = BanRecord.builder().bannedKeycloakId("kc-alice").reason("spam")
                .createdAt(START).build();
        when(repository.findActiveBans(eq("kc-alice"), any())).thenReturn(List.of(ban));
        when(mirror.read("kc-alice")).thenReturn(Optional.of(new SubjectBan("spam", null)));

        banService.createBan("kc-alice", "spam", "kc-admin", null);

        verify(mirror).publish("kc-alice", Optional.of(new SubjectBan("spam", null)));
        assertThatThrownBy(() -> policy.ensureNotBanned(alice)).isInstanceOf(UserBannedException.class);
        verify(mirror, times(2)).read("kc-alice");
    }

    @Test
    void removeInvalidatesTheCachedAnswer() {
        UUID banId = UUID.randomUUID();
        when(mirror.read("kc-alice")).thenReturn(Optional.of(new SubjectBan("spam", null)));
        assertThat(banService.isBanned("kc-alice")).isTrue();

        when(repository.existsById(banId)).thenReturn(true);
        when(repository.findById(banId)).thenReturn(Optional.of(
                BanRecord.builder().id(banId).bannedKeycloakId("kc-alice").build()));
        when(repository.findActiveBans(eq("kc-alice"), any())).thenReturn(List.of());
        when(mirror.read("kc-alice")).thenReturn(Optional.empty());

        assertThat(banService.removeBan(banId)).isTrue();

        verify(mirror).publish("kc-alice", Optional.empty());
        assertThat(banService.isBanned("kc-alice")).isFalse();
        policy.ensureNotBanned(alice);
        verify(mirror, times(2)).read("kc-alice");
    }

    @Test
    void timedBanIsNotServedFromCachePastItsExpiry() {
        Instant expiry = START.plusSeconds(10);
        when(mirror.read("kc-alice")).thenReturn(Optional.of(new SubjectBan("cooldown", expiry)));
        assertThat(banService.isBanned("kc-alice")).isTrue();
        clock.advance(Duration.ofSeconds(11));
        when(mirror.read("kc-alice")).thenReturn(Optional.empty());
        assertThat(banService.isBanned("kc-alice")).isFalse();
    }

    @Test
    void redisDownFallsBackToPostgresOncePerTtl() {
        when(mirror.read("kc-alice")).thenThrow(new RedisUnavailableException("down", null));
        when(repository.findActiveBans(eq("kc-alice"), any())).thenReturn(List.of(
                BanRecord.builder().bannedKeycloakId("kc-alice").reason("spam").createdAt(START).build()));

        for (int i = 0; i < 100; i++) {
            assertThat(banService.isBanned("kc-alice")).isTrue();
        }
        verify(repository, times(1)).findActiveBans(eq("kc-alice"), any());
    }

    @Test
    void bothStoresDownFailsOpen() {
        when(mirror.read("kc-alice")).thenThrow(new RedisUnavailableException("down", null));
        when(repository.findActiveBans(eq("kc-alice"), any())).thenThrow(new RuntimeException("db down"));
        assertThat(banService.isBanned("kc-alice")).isFalse();
        policy.ensureNotBanned(alice);
    }

    @Test
    void guestsAndBlankSubjectsNeverRead() {
        policy.ensureNotBanned(AuthenticatedUser.guest());
        assertThat(banService.isBanned(null)).isFalse();
        assertThat(banService.isBanned(" ")).isFalse();
        verify(mirror, never()).read(anyString());
    }

    @Test
    void subjectBanCheckerUsesTheSameCache() {
        when(mirror.read("kc-alice")).thenReturn(Optional.of(new SubjectBan("spam", START.plusSeconds(3600))));
        SubjectBanCheckerImpl checker = new SubjectBanCheckerImpl(banService);
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> checker.ensureNotBanned("kc-alice"))
                    .isInstanceOf(SubjectBannedException.class);
        }
        policy.ensureNotBanned(AuthenticatedUser.of("kc-bob", "bob", null, List.of()));
        verify(mirror, times(1)).read("kc-alice");
    }

    @Test
    void effectiveBanPrefersPermanentThenLatestExpiry() {
        BanRecord shortBan = BanRecord.builder().bannedKeycloakId("s").reason("short")
                .createdAt(START).expiresAt(START.plusSeconds(60)).build();
        BanRecord longBan = BanRecord.builder().bannedKeycloakId("s").reason("long")
                .createdAt(START).expiresAt(START.plusSeconds(600)).build();
        BanRecord permanent = BanRecord.builder().bannedKeycloakId("s").reason("forever")
                .createdAt(START.minusSeconds(5)).build();

        when(repository.findActiveBans(eq("s"), any())).thenReturn(List.of(shortBan, longBan));
        when(mirror.read("s")).thenThrow(new RedisUnavailableException("down", null));
        assertThat(banService.findActiveBanCached("s")).contains(new SubjectBan("long", START.plusSeconds(600)));

        clock.advance(TTL.plusSeconds(1));
        when(repository.findActiveBans(eq("s"), any())).thenReturn(List.of(shortBan, permanent, longBan));
        assertThat(banService.findActiveBanCached("s")).contains(new SubjectBan("forever", null));
    }
}
