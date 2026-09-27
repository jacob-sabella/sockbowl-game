package com.soulsoftworks.sockbowlgame.service.ban;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.soulsoftworks.sockbowlgame.model.entity.BanRecord;
import com.soulsoftworks.sockbowlgame.ratelimit.IpBanChecker;
import com.soulsoftworks.sockbowlgame.ratelimit.SubjectBanChecker;
import com.soulsoftworks.sockbowlgame.ratelimit.SubjectBannedException;
import com.soulsoftworks.sockbowlgame.ratelimit.UsageKeys;
import com.soulsoftworks.sockbowlgame.service.BanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Subject bans are published to Redis as {@code ban:{sub}} (D8, AB-01; plan
 * m4-limits WP-G4) against real Postgres and Redis: create writes the key with
 * a TTL matching the expiry, remove deletes it, and {@link BanService#resyncAll}
 * repopulates a flushed Redis and removes stale keys.
 */
class BanRedisMirrorIT extends BanITSupport {

    @Autowired
    BanService banService;
    @Autowired
    SubjectBanChecker subjectBanChecker;
    @Autowired
    IpBanChecker ipBanChecker;

    private String sub;

    @BeforeEach
    void setUp() {
        resetStores();
        sub = "kc-" + UUID.randomUUID();
    }

    private String mirrored(String subject) {
        return redis.sync().get(UsageKeys.ban(subject));
    }

    private long pttl(String subject) {
        return redis.sync().pttl(UsageKeys.ban(subject));
    }

    @Test
    void realCheckersReplaceTheNoOpFallbacks() {
        assertThat(subjectBanChecker).isInstanceOf(SubjectBanCheckerImpl.class);
        assertThat(ipBanChecker).isInstanceOf(IpBanService.class);
    }

    @Test
    void createWritesTheKeyWithATtlMatchingTheExpiry() {
        Instant expiresAt = clock.instant().plus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MILLIS);
        banService.createBan(sub, "spamming the buzzer", "kc-admin", expiresAt);

        JsonObject json = JsonParser.parseString(mirrored(sub)).getAsJsonObject();
        assertThat(json.get("reason").getAsString()).isEqualTo("spamming the buzzer");
        assertThat(Instant.parse(json.get("expiresAt").getAsString())).isEqualTo(expiresAt);
        assertThat(pttl(sub)).isBetween(Duration.ofMinutes(59).toMillis(), Duration.ofHours(1).toMillis());
    }

    @Test
    void permanentBanHasNoTtlAndANullExpiry() {
        banService.createBan(sub, "abuse", "kc-admin", null);

        assertThat(mirrored(sub)).isEqualTo("{\"reason\":\"abuse\",\"expiresAt\":null}");
        assertThat(pttl(sub)).isEqualTo(-1L);
    }

    @Test
    void removeDeletesTheKey() {
        BanRecord ban = banService.createBan(sub, "abuse", "kc-admin", clock.instant().plus(Duration.ofDays(1)));
        assertThat(mirrored(sub)).isNotNull();

        assertThat(banService.removeBan(ban.getId())).isTrue();

        assertThat(redis.sync().exists(UsageKeys.ban(sub))).isZero();
    }

    @Test
    void removingOneOfTwoBansRepublishesTheOther() {
        Instant shortExpiry = clock.instant().plus(Duration.ofMinutes(10)).truncatedTo(ChronoUnit.MILLIS);
        banService.createBan(sub, "short", "kc-admin", shortExpiry);
        BanRecord permanent = banService.createBan(sub, "forever", "kc-admin", null);
        assertThat(pttl(sub)).isEqualTo(-1L);

        banService.removeBan(permanent.getId());

        JsonObject json = JsonParser.parseString(mirrored(sub)).getAsJsonObject();
        assertThat(json.get("reason").getAsString()).isEqualTo("short");
        assertThat(pttl(sub)).isBetween(Duration.ofMinutes(9).toMillis(), Duration.ofMinutes(10).toMillis());
        // Two active bans once broke the Optional lookup (non-unique result).
        assertThat(banService.findActiveBan(sub)).isPresent();
    }

    @Test
    void resyncRepopulatesAFlushedRedisAndDropsStaleKeys() {
        String other = "kc-" + UUID.randomUUID();
        banService.createBan(sub, "one", "kc-admin", null);
        banService.createBan(other, "two", "kc-admin", clock.instant().plus(Duration.ofHours(2)));

        redis.sync().flushdb();
        redis.sync().set(UsageKeys.ban("kc-ghost"), "{\"reason\":\"gone\",\"expiresAt\":null}");
        assertThat(mirrored(sub)).isNull();

        assertThat(banService.resyncAll()).isEqualTo(2);

        assertThat(mirrored(sub)).contains("\"one\"");
        assertThat(pttl(sub)).isEqualTo(-1L);
        assertThat(mirrored(other)).contains("\"two\"");
        assertThat(pttl(other)).isPositive();
        assertThat(mirrored("kc-ghost")).isNull();
    }

    @Test
    void checkerSeesCreateAndRemoveImmediately() {
        subjectBanChecker.ensureNotBanned(sub); // caches "not banned"

        BanRecord ban = banService.createBan(sub, "abuse", "kc-admin", null);
        assertThatThrownBy(() -> subjectBanChecker.ensureNotBanned(sub))
                .isInstanceOf(SubjectBannedException.class)
                .hasMessageContaining("abuse");

        banService.removeBan(ban.getId());
        subjectBanChecker.ensureNotBanned(sub);
    }

    @Test
    void banMadeOnAnotherInstanceIsSeenAfterTheCacheTtl() {
        subjectBanChecker.ensureNotBanned(sub); // cached "not banned" here
        // Another instance publishes a ban: only Redis changes on this one.
        redis.sync().set(UsageKeys.ban(sub), "{\"reason\":\"elsewhere\",\"expiresAt\":null}");
        subjectBanChecker.ensureNotBanned(sub); // still cached

        clock.advance(Duration.ofSeconds(31));

        assertThatThrownBy(() -> subjectBanChecker.ensureNotBanned(sub))
                .isInstanceOf(SubjectBannedException.class);
    }

    @Test
    void adminBanEndpointPublishesAndUnpublishes() throws Exception {
        String body = "{\"bannedKeycloakId\":\"" + sub + "\",\"reason\":\"via api\"}";
        String created = mvc.perform(post("/api/v1/admin/bans")
                        .with(jwt().jwt(j -> j.subject("kc-mod")).authorities(new SimpleGrantedAuthority("user:ban")))
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(mirrored(sub)).contains("via api");

        String id = JsonParser.parseString(created).getAsJsonObject().get("id").getAsString();
        mvc.perform(delete("/api/v1/admin/bans/" + id)
                        .with(jwt().jwt(j -> j.subject("kc-mod")).authorities(new SimpleGrantedAuthority("user:ban"))))
                .andExpect(status().isNoContent());
        assertThat(mirrored(sub)).isNull();
    }
}
