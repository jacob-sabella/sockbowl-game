package com.soulsoftworks.sockbowlgame.usage;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.redis.testcontainers.RedisContainer;
import com.soulsoftworks.sockbowlgame.model.entity.QuotaOverride;
import com.soulsoftworks.sockbowlgame.model.entity.User;
import com.soulsoftworks.sockbowlgame.model.request.CreateGameRequest;
import com.soulsoftworks.sockbowlgame.model.state.GameMode;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.state.GameSettings;
import com.soulsoftworks.sockbowlgame.model.state.ProctorType;
import com.soulsoftworks.sockbowlgame.ratelimit.RateLimitRedis;
import com.soulsoftworks.sockbowlgame.ratelimit.UsageKeys;
import com.soulsoftworks.sockbowlgame.repository.GameSessionRepository;
import com.soulsoftworks.sockbowlgame.repository.QuotaOverrideRepository;
import com.soulsoftworks.sockbowlgame.repository.UserRepository;
import com.soulsoftworks.sockbowlgame.service.BanService;
import com.soulsoftworks.sockbowlgame.util.MutableClock;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WP-G6 acceptance: {@code AdminUsageController} end to end (plan m4-limits
 * section 2.8, M4-AD-01) against a real Postgres and Redis Testcontainer, and
 * a {@link MockWebServer} standing in for sockbowl-questions' (WP-Q4, not yet
 * written when this class was authored) {@code content-counts} endpoint.
 *
 * <p>Redis rows are written directly by the test the way sockbowl-questions
 * and the rest of game's own M4 code would (the {@code UsageKeys} contract),
 * never through any questions-specific code, so this class does not depend on
 * Q4 landing.
 */
@Testcontainers
@SpringBootTest(properties = {
        "sockbowl.auth.enabled=true",
        // Re-enable JPA (the shared test properties exclude it) against Postgres.
        "spring.autoconfigure.exclude=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://127.0.0.1:1/realms/sockbowl",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/realms/sockbowl/protocol/openid-connect/certs",
        "spring.security.oauth2.client.provider.keycloak.token-uri=http://127.0.0.1:1/realms/sockbowl/protocol/openid-connect/token",
        "spring.security.oauth2.client.registration.questions-svc.provider=keycloak",
        "spring.security.oauth2.client.registration.questions-svc.client-id=sockbowl-game-backend",
        "spring.security.oauth2.client.registration.questions-svc.client-secret=test-secret",
        "spring.security.oauth2.client.registration.questions-svc.authorization-grant-type=client_credentials",
        "sockbowl.ai.server-key.daily-budget=200",
        // src/main/resources/application.properties never reaches the test
        // classpath (shadowed), so the tier defaults this class exercises are
        // declared here, mirroring the real ones for author/player.
        "sockbowl.quota.enabled=true",
        "sockbowl.quota.tiers.author.hosted-sessions=5",
        "sockbowl.quota.tiers.author.[ai.generations]=20",
        "sockbowl.quota.tiers.author.imports=10",
        "sockbowl.quota.tiers.author.packets-owned=300",
        "sockbowl.quota.tiers.player.hosted-sessions=1"
})
@AutoConfigureMockMvc
@Import(AdminUsageControllerIT.ItConfig.class)
class AdminUsageControllerIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
            .withDatabaseName("sockbowl_users");
    @Container
    static final RedisContainer REDIS = com.soulsoftworks.sockbowlgame.util.TestcontainersUtil.getRedisContainer();

    private static final MockWebServer QUESTIONS = new MockWebServer();
    private static final Instant START = Instant.parse("2026-09-27T12:00:00Z");

    static {
        try {
            QUESTIONS.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("sockbowl.redis.game-cache.hostname", REDIS::getHost);
        registry.add("sockbowl.redis.game-cache.port", () -> REDIS.getMappedPort(6379).toString());
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("sockbowl.questions.url", () -> QUESTIONS.url("/").toString());
    }

    @AfterAll
    static void closeQuestions() throws IOException {
        QUESTIONS.close();
    }

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mvc;
    @Autowired
    MutableClock clock;
    @Autowired
    RateLimitRedis redis;
    @Autowired
    UserRepository userRepository;
    @Autowired
    QuotaOverrideRepository quotaOverrideRepository;
    @Autowired
    GameSessionRepository gameSessionRepository;
    @Autowired
    BanService banService;

    private static final String BASE = "/api/v1/admin/usage";

    @BeforeEach
    void setUp() {
        clock.set(START);
        quotaOverrideRepository.deleteAll();
        userRepository.deleteAll();
        // Scoped reset (never flushdb: sockbowl.redis.game-cache also hosts
        // Redis OM's GameSessionIdx RediSearch index for this cached context).
        List<String> keys = new java.util.ArrayList<>(redis.sync().keys("usage:*"));
        keys.addAll(redis.sync().keys("quota:override:*"));
        keys.addAll(redis.sync().keys("rl:events"));
        keys.addAll(redis.sync().keys("ban:*"));
        if (!keys.isEmpty()) {
            redis.sync().del(keys.toArray(new String[0]));
        }
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("kc-admin"))
                .authorities(new SimpleGrantedAuthority("player"), new SimpleGrantedAuthority("admin:access"));
    }

    private static RequestPostProcessor player() {
        return jwt().jwt(j -> j.subject("kc-someplayer"))
                .authorities(new SimpleGrantedAuthority("player"));
    }

    private static RequestPostProcessor moderator() {
        return jwt().jwt(j -> j.subject("kc-somemod"))
                .authorities(new SimpleGrantedAuthority("player"), new SimpleGrantedAuthority("user:ban"));
    }

    private static RequestPostProcessor host(String sub, String... extraRoles) {
        String[] roles = new String[extraRoles.length + 2];
        roles[0] = "player";
        roles[1] = "game:host";
        System.arraycopy(extraRoles, 0, roles, 2, extraRoles.length);
        var pp = jwt().jwt(j -> j.subject(sub).claim("preferred_username", sub).claim("name", "Name " + sub)
                .claim("realm_access", java.util.Map.of("roles", List.of(roles))));
        return pp.authorities(java.util.Arrays.stream(roles)
                .map(SimpleGrantedAuthority::new).toArray(SimpleGrantedAuthority[]::new));
    }

    private User saveUser(String sub, String email, String name) {
        return userRepository.save(User.builder()
                .keycloakId(sub).email(email).name(name).lastLoginAt(clock.instant())
                .build());
    }

    private void enqueueContentCounts(String json) {
        QUESTIONS.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(json)
                .build());
    }

    @Test
    void anonymousGetsUnauthorized() throws Exception {
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/global")).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/events")).andExpect(status().isUnauthorized());
        mvc.perform(get(BASE + "/kc-x")).andExpect(status().isUnauthorized());
    }

    @Test
    void playerAndModeratorAreForbidden() throws Exception {
        for (RequestPostProcessor caller : List.of(player(), moderator())) {
            mvc.perform(get(BASE).with(caller)).andExpect(status().isForbidden());
            mvc.perform(get(BASE + "/global").with(caller)).andExpect(status().isForbidden());
            mvc.perform(get(BASE + "/kc-x").with(caller)).andExpect(status().isForbidden());
            mvc.perform(put(BASE + "/kc-x/quota/ai.generations").with(caller)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":5}"))
                    .andExpect(status().isForbidden());
            mvc.perform(post(BASE + "/kc-x/reset").with(caller)).andExpect(status().isForbidden());
        }
    }

    @Test
    void summaryReflectsRedisCountersOverrideAndActiveSessions() throws Exception {
        saveUser("kc-alice", "alice@example.com", "Alice");
        String today = UsageKeys.day(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));

        redis.sync().hset(UsageKeys.meta("kc-alice"), java.util.Map.of(
                "tier", "author", "lastSeenAt", clock.instant().toString()));
        redis.sync().set("usage:kc-alice:ai.generations:d:" + today, "5");
        redis.sync().set("usage:kc-alice:imports:d:" + today, "2");
        redis.sync().hset(UsageKeys.quotaOverride("kc-alice"), "ai.generations", "15");

        GameSession session = gameSessionRepository.save(GameSession.builder()
                .joinCode("JOIN01")
                .gameSettings(GameSettings.builder()
                        .gameMode(GameMode.QUIZ_BOWL_CLASSIC)
                        .proctorType(ProctorType.ONLINE_PROCTOR)
                        .build())
                .build());
        redis.sync().zadd(UsageKeys.sessions(UsageKeys.userPart("kc-alice")), clock.millis(), session.getId());

        enqueueContentCounts("{\"kc-alice\":{\"packetsOwned\":7,\"questionsCreated\":3}}");

        String body = mvc.perform(get(BASE).param("q", "alice").with(admin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonObject page = JsonParser.parseString(body).getAsJsonObject();
        JsonArray content = page.getAsJsonArray("content");
        assertThat(content).hasSize(1);
        JsonObject row = content.get(0).getAsJsonObject();
        assertThat(row.get("keycloakId").getAsString()).isEqualTo("kc-alice");
        assertThat(row.get("tier").getAsString()).isEqualTo("author");
        assertThat(row.get("banned").getAsBoolean()).isFalse();
        assertThat(row.get("activeSessions").getAsLong()).isEqualTo(1);
        assertThat(row.get("packetsOwned").getAsLong()).isEqualTo(7);
        assertThat(row.get("packetsOwnedUnavailable").getAsBoolean()).isFalse();

        JsonArray counters = row.getAsJsonArray("counters");
        JsonObject aiGen = findCounter(counters, "ai.generations");
        assertThat(aiGen.get("used").getAsLong()).isEqualTo(5);
        assertThat(aiGen.get("limit").getAsLong()).isEqualTo(15);
        assertThat(aiGen.get("overridden").getAsBoolean()).isTrue();

        JsonObject imports = findCounter(counters, "imports");
        assertThat(imports.get("used").getAsLong()).isEqualTo(2);
        assertThat(imports.get("limit").getAsLong()).isEqualTo(10);
        assertThat(imports.get("overridden").getAsBoolean()).isFalse();

        JsonObject hosted = findCounter(counters, "hosted-sessions");
        assertThat(hosted.get("used").getAsLong()).isEqualTo(1);
        assertThat(hosted.get("limit").getAsLong()).isEqualTo(5);

        JsonObject packets = findCounter(counters, "packets-owned");
        assertThat(packets.get("used").getAsLong()).isEqualTo(7);
        assertThat(packets.get("limit").getAsLong()).isEqualTo(300);
    }

    @Test
    void bannedFlagReflectsARealBan() throws Exception {
        saveUser("kc-bob", "bob@example.com", "Bob");
        banService.createBan("kc-bob", "test reason", "kc-admin", null);

        enqueueContentCounts("{\"kc-bob\":{\"packetsOwned\":0,\"questionsCreated\":0}}");

        mvc.perform(get(BASE + "/kc-bob").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.keycloakId").value("kc-bob"))
                .andExpect(jsonPath("$.summary.banned").value(true));
    }

    @Test
    void detailIncludesLastIpsOverridesAndRecentEvents() throws Exception {
        saveUser("kc-carol", "carol@example.com", "Carol");
        redis.sync().zadd(UsageKeys.ips("kc-carol"), clock.millis(), "203.0.113.5");
        redis.sync().hset(UsageKeys.quotaOverride("kc-carol"), "imports", "3");
        redis.sync().xadd(UsageKeys.events(), java.util.Map.of(
                "ts", Long.toString(clock.millis()), "svc", "game", "policy", "create-session",
                "kind", "quota", "sub", "kc-carol", "ip", "203.0.113.5", "path", "/api/v1/session/create-new-game-session"));

        enqueueContentCounts("{\"kc-carol\":{\"packetsOwned\":0,\"questionsCreated\":0}}");

        mvc.perform(get(BASE + "/kc-carol").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastIps[0]").value("203.0.113.5"))
                .andExpect(jsonPath("$.overrides.imports").value(3))
                .andExpect(jsonPath("$.recentEvents[0].sub").value("kc-carol"))
                .andExpect(jsonPath("$.recentEvents[0].policy").value("create-session"));
    }

    @Test
    void detailIsNotFoundForAnUnknownUser() throws Exception {
        mvc.perform(get(BASE + "/kc-nobody").with(admin())).andExpect(status().isNotFound());
    }

    @Test
    void putQuotaWritesPostgresAndRedisAndNullClearsIt() throws Exception {
        saveUser("kc-dave", "dave@example.com", "Dave");

        mvc.perform(put(BASE + "/kc-dave/quota/ai.generations").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(7))
                .andExpect(jsonPath("$.overridden").value(true));

        List<QuotaOverride> saved = quotaOverrideRepository.findByKeycloakId("kc-dave");
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getLimitValue()).isEqualTo(7);
        assertThat(saved.get(0).getUpdatedBy()).isEqualTo("kc-admin");
        assertThat(redis.sync().hget(UsageKeys.quotaOverride("kc-dave"), "ai.generations")).isEqualTo("7");

        mvc.perform(put(BASE + "/kc-dave/quota/ai.generations").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overridden").value(false));

        assertThat(quotaOverrideRepository.findByKeycloakId("kc-dave")).isEmpty();
        assertThat(redis.sync().hget(UsageKeys.quotaOverride("kc-dave"), "ai.generations")).isNull();
    }

    @Test
    void putQuotaRejectsAnUnknownMetric() throws Exception {
        mvc.perform(put(BASE + "/kc-dave/quota/not-a-metric").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":7}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * G-M4-V1-09: {@code -1} means unlimited (D12-adjacent quota convention),
     * but anything more negative than that is nonsensical and previously
     * passed straight through to Postgres and the Redis mirror unvalidated.
     */
    @Test
    void putQuotaRejectsALimitBelowNegativeOne() throws Exception {
        saveUser("kc-dave", "dave@example.com", "Dave");

        mvc.perform(put(BASE + "/kc-dave/quota/ai.generations").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":-5}"))
                .andExpect(status().isBadRequest());

        assertThat(quotaOverrideRepository.findByKeycloakId("kc-dave")).isEmpty();
        assertThat(redis.sync().hget(UsageKeys.quotaOverride("kc-dave"), "ai.generations")).isNull();
    }

    @Test
    void putQuotaAcceptsNegativeOneAsUnlimited() throws Exception {
        saveUser("kc-dave", "dave@example.com", "Dave");

        mvc.perform(put(BASE + "/kc-dave/quota/ai.generations").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"limit\":-1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(-1))
                .andExpect(jsonPath("$.overridden").value(true));
    }

    @Test
    void resetClearsTodaysHostedSessionCounterSoALimitedActionSucceedsAgain() throws Exception {
        RequestPostProcessor eve = host("kc-eve");
        String createBody = new com.google.gson.Gson().toJson(CreateGameRequest.builder()
                .gameSettings(GameSettings.builder()
                        .gameMode(GameMode.QUIZ_BOWL_CLASSIC)
                        .proctorType(ProctorType.ONLINE_PROCTOR)
                        .build())
                .build());

        mvc.perform(post("/api/v1/session/create-new-game-session").with(eve)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isOk());
        // player tier is capped at 1 hosted session (test property): a second is refused.
        mvc.perform(post("/api/v1/session/create-new-game-session").with(eve)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isTooManyRequests());

        mvc.perform(post(BASE + "/kc-eve/reset").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"metric\":\"hosted-sessions\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/session/create-new-game-session").with(eve)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isOk());
    }

    @Test
    void resetRejectsAnUnknownMetric() throws Exception {
        mvc.perform(post(BASE + "/kc-eve/reset").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"metric\":\"not-a-metric\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void eventsComeBackNewestFirst() throws Exception {
        redis.sync().xadd(UsageKeys.events(), java.util.Map.of(
                "ts", Long.toString(clock.millis()), "svc", "game", "policy", "p1", "kind", "quota"));
        clock.advance(Duration.ofSeconds(1));
        redis.sync().xadd(UsageKeys.events(), java.util.Map.of(
                "ts", Long.toString(clock.millis()), "svc", "game", "policy", "p2", "kind", "quota"));

        String body = mvc.perform(get(BASE + "/events").param("limit", "10").with(admin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonArray events = JsonParser.parseString(body).getAsJsonArray();
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getAsJsonObject().get("policy").getAsString()).isEqualTo("p2");
        assertThat(events.get(1).getAsJsonObject().get("policy").getAsString()).isEqualTo("p1");
    }

    @Test
    void questionsBeingDownStillReturns200WithPacketsOwnedUnavailable() throws Exception {
        saveUser("kc-frank", "frank@example.com", "Frank");
        QUESTIONS.enqueue(new MockResponse.Builder().code(503).body("down").build());

        mvc.perform(get(BASE + "/kc-frank").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.packetsOwnedUnavailable").value(true))
                .andExpect(jsonPath("$.summary.packetsOwned").doesNotExist());
    }

    private static JsonObject findCounter(JsonArray counters, String metric) {
        for (var element : counters) {
            JsonObject obj = element.getAsJsonObject();
            if (metric.equals(obj.get("metric").getAsString())) {
                return obj;
            }
        }
        throw new AssertionError("No counter for metric " + metric);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ItConfig {

        /** Replaces LimitsClockConfig's system clock (it backs off). */
        @Bean
        MutableClock limitsTestClock() {
            return new MutableClock(START);
        }
    }
}
