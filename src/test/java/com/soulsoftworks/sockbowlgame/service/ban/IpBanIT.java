package com.soulsoftworks.sockbowlgame.service.ban;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.soulsoftworks.sockbowlgame.ratelimit.UsageKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IP/CIDR bans end to end (D8, AB-02; plan m4-limits WP-G4): the admin API,
 * Postgres, the {@code ipban:all} Redis mirror and the {@code IpBanChecker}
 * bean the request guard calls. A banned address gets 403 {@code ip_banned};
 * once the clock passes the expiry, or the ban is removed, it gets through
 * again (recovery).
 */
class IpBanIT extends BanITSupport {

    private static final String IP_BANS = "/api/v1/admin/bans/ip";
    private static final String PUBLIC_PATH = "/api/v1/auth/status";
    private static final String BANNED_IP = "203.0.113.7";

    @Autowired
    IpBanService ipBanService;

    @BeforeEach
    void setUp() {
        resetStores();
        ipBanService.resyncAll();
    }

    private static RequestPostProcessor moderator() {
        return jwt().jwt(j -> j.subject("kc-mod"))
                .authorities(new SimpleGrantedAuthority("player"), new SimpleGrantedAuthority("user:ban"));
    }

    private static RequestPostProcessor player() {
        return jwt().jwt(j -> j.subject("kc-player"))
                .authorities(new SimpleGrantedAuthority("player"), new SimpleGrantedAuthority("game:host"));
    }

    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private ResultActions createBan(String body) throws Exception {
        return mvc.perform(post(IP_BANS).with(moderator())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String createBanOk(String cidr, long ttlSeconds) throws Exception {
        String json = createBan("{\"cidr\":\"" + cidr + "\",\"reason\":\"abuse\",\"ttlSeconds\":" + ttlSeconds + "}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonParser.parseString(json).getAsJsonObject().get("id").getAsString();
    }

    @Test
    void bannedAddressGets403UntilTheBanExpires() throws Exception {
        mvc.perform(get(PUBLIC_PATH).with(from(BANNED_IP))).andExpect(status().isOk());

        Instant expectedExpiry = clock.instant().plus(Duration.ofHours(1));
        String json = createBan("{\"cidr\":\"" + BANNED_IP + "/32\",\"reason\":\"buzzer spam\",\"ttlSeconds\":3600}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cidr").value(BANNED_IP + "/32"))
                .andExpect(jsonPath("$.reason").value("buzzer spam"))
                .andExpect(jsonPath("$.bannedBy").value("kc-mod"))
                .andExpect(jsonPath("$.expiresAt").value(expectedExpiry.toString()))
                .andReturn().getResponse().getContentAsString();
        String id = JsonParser.parseString(json).getAsJsonObject().get("id").getAsString();

        // Trigger: the banned address is rejected with the D8 body; a neighbour is not.
        mvc.perform(get(PUBLIC_PATH).with(from(BANNED_IP)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("ip_banned"))
                .andExpect(jsonPath("$.expiresAt").value(expectedExpiry.toString()));
        mvc.perform(get(PUBLIC_PATH).with(from("203.0.113.8"))).andExpect(status().isOk());

        // Mirrored for questions and the other instances.
        String mirrored = redis.sync().hget(UsageKeys.ipBans(), id);
        JsonObject value = JsonParser.parseString(mirrored).getAsJsonObject();
        assertThat(value.get("cidr").getAsString()).isEqualTo(BANNED_IP + "/32");
        assertThat(value.get("expiresAtEpochMs").getAsLong()).isEqualTo(expectedExpiry.toEpochMilli());

        // Recovery: past the TTL the same address gets through.
        clock.advance(Duration.ofHours(1).plusSeconds(1));
        mvc.perform(get(PUBLIC_PATH).with(from(BANNED_IP))).andExpect(status().isOk());
        mvc.perform(get(IP_BANS).with(moderator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void rangeBanCoversTheWholeRange() throws Exception {
        createBanOk("198.51.100.0/24", 600);
        mvc.perform(get(PUBLIC_PATH).with(from("198.51.100.200"))).andExpect(status().isForbidden());
        mvc.perform(get(PUBLIC_PATH).with(from("198.51.101.1"))).andExpect(status().isOk());
    }

    @Test
    void ipv6BanAppliesToTheRawAddress() throws Exception {
        createBanOk("2001:db8:abcd::/48", 600);
        mvc.perform(get(PUBLIC_PATH).with(from("2001:db8:abcd:7::1"))).andExpect(status().isForbidden());
        mvc.perform(get(PUBLIC_PATH).with(from("2001:db8:abce::1"))).andExpect(status().isOk());
    }

    @Test
    void removingABanLetsTheAddressThrough() throws Exception {
        String id = createBanOk(BANNED_IP, 3600);
        mvc.perform(get(PUBLIC_PATH).with(from(BANNED_IP))).andExpect(status().isForbidden());

        mvc.perform(delete(IP_BANS + "/" + id).with(moderator())).andExpect(status().isNoContent());

        mvc.perform(get(PUBLIC_PATH).with(from(BANNED_IP))).andExpect(status().isOk());
        assertThat(redis.sync().hexists(UsageKeys.ipBans(), id)).isFalse();
        mvc.perform(delete(IP_BANS + "/" + id).with(moderator())).andExpect(status().isNotFound());
    }

    @Test
    void listShowsActiveBansNewestFirst() throws Exception {
        createBanOk("192.0.2.1", 600);
        clock.advance(Duration.ofSeconds(5));
        createBanOk("192.0.2.2", 600);

        String json = mvc.perform(get(IP_BANS).with(moderator()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonArray bans = JsonParser.parseString(json).getAsJsonArray();
        assertThat(bans).hasSize(2);
        assertThat(bans.get(0).getAsJsonObject().get("cidr").getAsString()).isEqualTo("192.0.2.2/32");
    }

    @Test
    void tooBroadRangesAreRejected() throws Exception {
        createBan("{\"cidr\":\"10.0.0.0/8\",\"ttlSeconds\":3600}").andExpect(status().isBadRequest());
        createBan("{\"cidr\":\"10.0.0.0/15\",\"ttlSeconds\":3600}").andExpect(status().isBadRequest());
        createBan("{\"cidr\":\"2001:db8::/32\",\"ttlSeconds\":3600}").andExpect(status().isBadRequest());
        createBan("{\"cidr\":\"10.1.0.0/16\",\"ttlSeconds\":3600}").andExpect(status().isCreated());
        createBan("{\"cidr\":\"2001:db8:1::/48\",\"ttlSeconds\":3600}").andExpect(status().isCreated());
        assertThat(ipBanRepository.count()).isEqualTo(2);
    }

    @Test
    void invalidRequestsAreRejected() throws Exception {
        // No expiry: IP bans must expire.
        createBan("{\"cidr\":\"" + BANNED_IP + "\"}").andExpect(status().isBadRequest());
        // Longer than the 30-day cap.
        createBan("{\"cidr\":\"" + BANNED_IP + "\",\"ttlSeconds\":" + Duration.ofDays(31).toSeconds() + "}")
                .andExpect(status().isBadRequest());
        // Both forms at once.
        createBan("{\"cidr\":\"" + BANNED_IP + "\",\"ttlSeconds\":60,\"expiresAt\":\""
                + clock.instant().plusSeconds(60) + "\"}").andExpect(status().isBadRequest());
        // Past expiry, bad timestamp, bad address, blank, non-positive TTL.
        createBan("{\"cidr\":\"" + BANNED_IP + "\",\"expiresAt\":\"" + clock.instant().minusSeconds(1) + "\"}")
                .andExpect(status().isBadRequest());
        createBan("{\"cidr\":\"" + BANNED_IP + "\",\"expiresAt\":\"tomorrow\"}").andExpect(status().isBadRequest());
        createBan("{\"cidr\":\"example.com\",\"ttlSeconds\":60}").andExpect(status().isBadRequest());
        createBan("{\"cidr\":\"\",\"ttlSeconds\":60}").andExpect(status().isBadRequest());
        createBan("{\"cidr\":\"" + BANNED_IP + "\",\"ttlSeconds\":0}").andExpect(status().isBadRequest());
        assertThat(ipBanRepository.count()).isZero();

        // An explicit expiresAt within the cap is fine.
        Instant at = clock.instant().plus(Duration.ofDays(30)).minusSeconds(1);
        createBan("{\"cidr\":\"" + BANNED_IP + "\",\"expiresAt\":\"" + at + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.expiresAt").value(at.toString()));
    }

    @Test
    void endpointNeedsUserBan() throws Exception {
        String body = "{\"cidr\":\"" + BANNED_IP + "\",\"ttlSeconds\":60}";
        mvc.perform(post(IP_BANS).with(player()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(get(IP_BANS).with(player())).andExpect(status().isForbidden());
        mvc.perform(delete(IP_BANS + "/" + UUID.randomUUID()).with(player())).andExpect(status().isForbidden());
        mvc.perform(post(IP_BANS).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(IP_BANS)).andExpect(status().isUnauthorized());
        assertThat(ipBanRepository.count()).isZero();
    }

    @Test
    void banMirroredByAnotherInstanceIsPickedUpOnRefresh() {
        Instant expiry = clock.instant().plus(Duration.ofMinutes(10));
        redis.sync().hset(UsageKeys.ipBans(), Map.of(
                UUID.randomUUID().toString(),
                "{\"cidr\":\"192.0.2.99/32\",\"expiresAtEpochMs\":" + expiry.toEpochMilli() + "}",
                "expired-one",
                "{\"cidr\":\"192.0.2.98/32\",\"expiresAtEpochMs\":" + clock.instant().minusSeconds(1).toEpochMilli() + "}"));
        assertThat(ipBanService.findActiveBan("192.0.2.99")).isEmpty();

        ipBanService.refresh();

        assertThat(ipBanService.findActiveBan("192.0.2.99")).isPresent();
        assertThat(ipBanService.findActiveBan("192.0.2.98")).isEmpty();
        assertThat(redis.sync().hexists(UsageKeys.ipBans(), "expired-one")).isFalse();
    }

    @Test
    void resyncRepopulatesAFlushedMirror() throws Exception {
        String id = createBanOk(BANNED_IP, 3600);
        redis.sync().flushdb();

        assertThat(ipBanService.resyncAll()).isEqualTo(1);

        assertThat(redis.sync().hget(UsageKeys.ipBans(), id)).contains(BANNED_IP + "/32");
        ipBanService.refresh();
        mvc.perform(get(PUBLIC_PATH).with(from(BANNED_IP))).andExpect(status().isForbidden());
    }
}
