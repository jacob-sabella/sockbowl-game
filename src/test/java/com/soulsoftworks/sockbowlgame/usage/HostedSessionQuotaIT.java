package com.soulsoftworks.sockbowlgame.usage;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.redis.testcontainers.RedisContainer;
import com.soulsoftworks.sockbowlgame.model.entity.User;
import com.soulsoftworks.sockbowlgame.model.entity.UserGameHistory;
import com.soulsoftworks.sockbowlgame.model.request.CreateGameRequest;
import com.soulsoftworks.sockbowlgame.model.state.GameMode;
import com.soulsoftworks.sockbowlgame.model.state.GameSettings;
import com.soulsoftworks.sockbowlgame.model.state.ProctorType;
import com.soulsoftworks.sockbowlgame.ratelimit.RateLimitRedis;
import com.soulsoftworks.sockbowlgame.ratelimit.UsageKeys;
import com.soulsoftworks.sockbowlgame.repository.GameSessionRepository;
import com.soulsoftworks.sockbowlgame.repository.UserGameHistoryRepository;
import com.soulsoftworks.sockbowlgame.repository.UserRepository;
import com.soulsoftworks.sockbowlgame.service.BanService;
import com.soulsoftworks.sockbowlgame.service.SessionService;
import com.soulsoftworks.sockbowlgame.service.UserService;
import com.soulsoftworks.sockbowlgame.service.UserUsedQuestionService;
import com.soulsoftworks.sockbowlgame.util.MutableClock;
import com.soulsoftworks.sockbowlgame.util.TestcontainersUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WP-G5 acceptance for the concurrent {@code hosted-sessions} quota (D10; plan
 * m4-limits section 2.3), through the real
 * {@code POST /api/v1/session/create-new-game-session} endpoint: guests are
 * counted per IP, users per subject, overrides win, admin is unlimited, and
 * the quota recovers both by idle timeout and by the session document simply
 * being gone.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "sockbowl.auth.enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://127.0.0.1:1/realms/sockbowl",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/realms/sockbowl/protocol/openid-connect/certs",
        "spring.security.oauth2.client.provider.keycloak.token-uri=http://127.0.0.1:1/realms/sockbowl/protocol/openid-connect/token",
        "spring.security.oauth2.client.registration.questions-svc.provider=keycloak",
        "spring.security.oauth2.client.registration.questions-svc.client-id=sockbowl-game-backend",
        "spring.security.oauth2.client.registration.questions-svc.client-secret=test-secret",
        "spring.security.oauth2.client.registration.questions-svc.authorization-grant-type=client_credentials",
        "sockbowl.questions.url=http://127.0.0.1:1/",
        // src/main/resources/application.properties (where WP-G1 put the real
        // sockbowl.quota.tiers.* defaults) never reaches the test classpath - a
        // same-named src/test/resources/application.properties shadows it - so
        // this is where the class actually under test declares the tier limits
        // it needs (mirrors application.properties' own guest=2, player=3).
        "sockbowl.quota.tiers.guest.hosted-sessions=2",
        "sockbowl.quota.tiers.player.hosted-sessions=3"
})
@AutoConfigureMockMvc
@Import(HostedSessionQuotaIT.ClockConfig.class)
class HostedSessionQuotaIT {

    @Container
    private static final RedisContainer REDIS = TestcontainersUtil.getRedisContainer();

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("sockbowl.redis.game-cache.hostname", REDIS::getHost);
        registry.add("sockbowl.redis.game-cache.port", () -> REDIS.getMappedPort(6379).toString());
    }

    private static final String CREATE = "/api/v1/session/create-new-game-session";
    private static final Instant START = Instant.parse("2026-09-27T12:00:00Z");

    private final Gson gson = new Gson();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SessionService sessionService;

    @Autowired
    private RateLimitRedis rateLimitRedis;

    @Autowired
    private MutableClock clock;

    @Autowired
    private GameSessionRepository gameSessionRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;
    @MockitoBean
    private BanService banService;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private UserUsedQuestionService usedQuestionService;
    @MockitoBean
    private UserRepository userRepository;
    @MockitoBean
    private UserGameHistoryRepository userGameHistoryRepository;

    @BeforeEach
    void stubs() {
        clock.set(START);
        // NOT flushdb(): sockbowl.redis.game-cache is the same Redis database
        // Redis OM uses for GameSession, whose RediSearch index is created
        // once for this whole (cached) test context. A flushdb() here wipes
        // that index along with our own keys, and since it's never recreated
        // mid-class every test after the first would fail with "No such
        // index ... GameSessionIdx". Scope the reset to only the key
        // prefixes this WP's quota/usage code owns instead.
        List<String> keys = new java.util.ArrayList<>(rateLimitRedis.sync().keys("usage:*"));
        keys.addAll(rateLimitRedis.sync().keys("quota:override:*"));
        if (!keys.isEmpty()) {
            rateLimitRedis.sync().del(keys.toArray(new String[0]));
        }
        when(banService.isBanned(anyString())).thenReturn(false);
        when(userRepository.findByKeycloakId(anyString())).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) {
                u.setId(UUID.randomUUID());
            }
            return u;
        });
        when(userGameHistoryRepository.save(any(UserGameHistory.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static RequestPostProcessor userToken(String sub, String... roles) {
        return jwt().jwt(j -> j.subject(sub)
                        .claim("azp", "sockbowl-game")
                        .claim("preferred_username", sub)
                        .claim("name", "Name " + sub)
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(Arrays.stream(roles).map(SimpleGrantedAuthority::new)
                        .toArray(GrantedAuthority[]::new));
    }

    private static RequestPostProcessor host(String sub, String... extraRoles) {
        String[] roles = new String[extraRoles.length + 2];
        roles[0] = "player";
        roles[1] = "game:host";
        System.arraycopy(extraRoles, 0, roles, 2, extraRoles.length);
        return userToken(sub, roles);
    }

    private static final RequestPostProcessor GUEST = r -> r;

    private String createBody() {
        return gson.toJson(CreateGameRequest.builder()
                .gameSettings(GameSettings.builder()
                        .gameMode(GameMode.QUIZ_BOWL_CLASSIC)
                        .proctorType(ProctorType.ONLINE_PROCTOR)
                        .build())
                .build());
    }

    private MvcResult create(RequestPostProcessor caller, int expected) throws Exception {
        return mvc.perform(post(CREATE).with(caller)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().is(expected))
                .andReturn();
    }

    private String idOf(MvcResult r) throws Exception {
        return gson.fromJson(r.getResponse().getContentAsString(), JsonObject.class).get("id").getAsString();
    }

    @Test
    void guestQuotaTripsAtTwoAndRecoversAfterIdleTimeout() throws Exception {
        create(GUEST, 200);
        create(GUEST, 200);

        MvcResult rejected = create(GUEST, 429);
        JsonObject body = gson.fromJson(rejected.getResponse().getContentAsString(), JsonObject.class);
        assertThat(body.get("error").getAsString()).isEqualTo("quota_exceeded");
        assertThat(body.get("metric").getAsString()).isEqualTo(UsageKeys.HOSTED_SESSIONS);
        assertThat(body.get("limit").getAsLong()).isEqualTo(2);
        assertThat(body.get("used").getAsLong()).isEqualTo(2);
        assertThat(body.get("resetsAt").isJsonNull()).isTrue();

        // No activity for 31 minutes: both sessions have gone idle.
        clock.advance(Duration.ofMinutes(31));
        create(GUEST, 200);
    }

    @Test
    void quotaRecoversWhenTheSessionDocumentIsGone() throws Exception {
        MvcResult first = create(GUEST, 200);
        create(GUEST, 200);
        create(GUEST, 429);

        // The session ends (its Redis OM document is deleted/expired) well
        // before the idle timeout would otherwise free the slot.
        clock.advance(Duration.ofMinutes(1));
        deleteSession(idOf(first));

        create(GUEST, 200);
    }

    @Test
    void authenticatedPlayerGetsThreeAndAnOverrideGrantsAFourth() throws Exception {
        RequestPostProcessor alice = host("kc-alice");
        create(alice, 200);
        create(alice, 200);
        create(alice, 200);
        create(alice, 429);

        rateLimitRedis.sync().hset(UsageKeys.quotaOverride("kc-alice"), UsageKeys.HOSTED_SESSIONS, "4");
        create(alice, 200);
        create(alice, 429);
    }

    @Test
    void adminIsUnlimited() throws Exception {
        RequestPostProcessor admin = host("kc-admin", "admin");
        for (int i = 0; i < 10; i++) {
            create(admin, 200);
        }
    }

    @Test
    void differentGuestOwnersAreCountedIndependently() throws Exception {
        // Same MockMvc "IP" (127.0.0.1) but a different signed-in subject is a
        // different owner from the anonymous guest bucket.
        create(GUEST, 200);
        create(GUEST, 200);
        create(GUEST, 429);

        create(host("kc-bob"), 200);
        create(host("kc-bob"), 200);
        create(host("kc-bob"), 200);
        create(host("kc-bob"), 429);
    }

    private void deleteSession(String id) {
        assertThat(sessionService.getGameSessionById(id)).as("exists before delete").isNotNull();
        gameSessionRepository.deleteById(id);
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class ClockConfig {
        @Bean
        MutableClock mutableClock() {
            return new MutableClock(START);
        }
    }
}
