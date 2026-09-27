package com.soulsoftworks.sockbowlgame.ratelimit;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.redis.testcontainers.RedisContainer;
import com.soulsoftworks.sockbowlgame.model.entity.User;
import com.soulsoftworks.sockbowlgame.model.entity.UserGameHistory;
import com.soulsoftworks.sockbowlgame.model.request.CreateGameRequest;
import com.soulsoftworks.sockbowlgame.model.state.GameMode;
import com.soulsoftworks.sockbowlgame.model.state.GameSettings;
import com.soulsoftworks.sockbowlgame.model.state.ProctorType;
import com.soulsoftworks.sockbowlgame.repository.UserGameHistoryRepository;
import com.soulsoftworks.sockbowlgame.repository.UserRepository;
import com.soulsoftworks.sockbowlgame.service.BanService;
import com.soulsoftworks.sockbowlgame.service.UserService;
import com.soulsoftworks.sockbowlgame.service.UserUsedQuestionService;
import com.soulsoftworks.sockbowlgame.util.MutableClock;
import com.soulsoftworks.sockbowlgame.util.TestcontainersUtil;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Shared context for the auth-on request-guard ITs (plan m4-limits WP-G2):
 * the full application with {@code sockbowl.auth.enabled=true}, the real
 * security chain, the shipped {@code sockbowl.ratelimit.*} policies with the
 * limiter enabled, a Redis Testcontainer for both the limiter and Redis OM, and
 * a {@link MutableClock} so recovery is proven by advancing time, never by
 * sleeping. Only the JPA-backed collaborators and the {@link JwtDecoder} are
 * mocked (as in {@code GameSessionControllerAuthIT}); the {@code jwt()}
 * post-processor supplies validated tokens.
 *
 * <p>Every test uses its own client IPs and subjects (see {@link #nextIp()}),
 * so buckets never leak between tests even though the context, Redis and clock
 * are shared.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "sockbowl.auth.enabled=true",
        "sockbowl.quota.enabled=false",
        // A cross-origin caller (MockMvc serves http://localhost, so that origin would be same-origin).
        "sockbowl.websocket.allowed-origins=http://localhost:4200",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://127.0.0.1:1/realms/sockbowl",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/realms/sockbowl/protocol/openid-connect/certs",
        "spring.security.oauth2.client.provider.keycloak.token-uri=http://127.0.0.1:1/realms/sockbowl/protocol/openid-connect/token",
        "spring.security.oauth2.client.registration.questions-svc.provider=keycloak",
        "spring.security.oauth2.client.registration.questions-svc.client-id=sockbowl-game-backend",
        "spring.security.oauth2.client.registration.questions-svc.client-secret=test-secret",
        "spring.security.oauth2.client.registration.questions-svc.authorization-grant-type=client_credentials",
        "sockbowl.questions.url=http://127.0.0.1:1/"
})
@AutoConfigureMockMvc
@Import(RequestGuardAuthOnITSupport.ClockConfig.class)
abstract class RequestGuardAuthOnITSupport {

    static final String CREATE = "/api/v1/session/create-new-game-session";
    static final String JOIN_BY_CODE = "/api/v1/session/join-game-session-by-code";
    static final String USED_QUESTIONS = "/api/v1/user/used-questions";
    static final String AUTH_STATUS = "/api/v1/auth/status";
    static final String ADMIN_BANS = "/api/v1/admin/bans";
    static final String ORIGIN = "http://localhost:4200";

    static final MutableClock CLOCK = MutableClock.startingNow();

    private static final AtomicInteger IP_SEQ = new AtomicInteger();

    /**
     * Started once for every subclass and never stopped by JUnit (Ryuk removes
     * it): the subclasses share one cached Spring context, which must keep
     * pointing at the same Redis. A per-class {@code @Container} would be
     * stopped after the first class while the cached context still used it.
     */
    static final RedisContainer REDIS = TestcontainersUtil.getRedisContainer();

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("sockbowl.redis.game-cache.hostname", REDIS::getHost);
        registry.add("sockbowl.redis.game-cache.port", () -> REDIS.getMappedPort(6379).toString());
        ShippedLimitProperties.registerRateLimits(registry);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        Clock testClock() {
            return CLOCK;
        }
    }

    final Gson gson = new Gson();

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JwtDecoder jwtDecoder;
    @MockitoBean
    BanService banService;
    @MockitoBean
    UserService userService;
    @MockitoBean
    UserUsedQuestionService usedQuestionService;
    @MockitoBean
    UserRepository userRepository;
    @MockitoBean
    UserGameHistoryRepository userGameHistoryRepository;

    @BeforeEach
    void stubCollaborators() {
        when(banService.isBanned(anyString())).thenReturn(false);
        when(banService.listActiveBans()).thenReturn(List.of());
        when(userRepository.findByKeycloakId(anyString())).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) {
                u.setId(UUID.randomUUID());
            }
            return u;
        });
        when(userGameHistoryRepository.save(any(UserGameHistory.class))).thenAnswer(inv -> inv.getArgument(0));
        User user = User.builder().id(UUID.randomUUID()).keycloakId("kc").name("n")
                .createdAt(Instant.now()).lastLoginAt(Instant.now()).build();
        when(userService.findOrCreateUser(anyString(), any(), any())).thenReturn(user);
        when(usedQuestionService.recordUsed(any(), any())).thenReturn(1);
        when(usedQuestionService.getUsedRemoteIds(any())).thenReturn(Set.of());
        when(jwtDecoder.decode(eq("garbage"))).thenThrow(new BadJwtException("bad"));
    }

    /** A fresh client address per call (10.42.x.y), so every test starts with full buckets. */
    static String nextIp() {
        int n = IP_SEQ.incrementAndGet();
        return "10.42." + (n / 250) + "." + (n % 250 + 1);
    }

    static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    static RequestPostProcessor userToken(String sub, String... roles) {
        return jwt().jwt(j -> j.subject(sub)
                        .claim("azp", "sockbowl-game")
                        .claim("preferred_username", sub)
                        .claim("name", "Name " + sub)
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(Arrays.stream(roles).map(SimpleGrantedAuthority::new)
                        .toArray(GrantedAuthority[]::new));
    }

    static RequestPostProcessor author(String sub) {
        return userToken(sub, "author", "player", "packet:read", "game:host");
    }

    static RequestPostProcessor player(String sub) {
        return userToken(sub, "player", "packet:read", "game:host");
    }

    static RequestPostProcessor moderator(String sub) {
        return userToken(sub, "moderator", "player", "user:ban");
    }

    static final RequestPostProcessor ANON = r -> r;

    static String createBody() {
        return new Gson().toJson(CreateGameRequest.builder()
                .gameSettings(GameSettings.builder()
                        .gameMode(GameMode.QUIZ_BOWL_CLASSIC)
                        .proctorType(ProctorType.ONLINE_PROCTOR)
                        .build())
                .build());
    }

    static String joinBody(String code) {
        JsonObject o = new JsonObject();
        o.addProperty("joinCode", code);
        o.addProperty("name", "Guesty");
        return o.toString();
    }

    static MediaType json() {
        return MediaType.APPLICATION_JSON;
    }
}
