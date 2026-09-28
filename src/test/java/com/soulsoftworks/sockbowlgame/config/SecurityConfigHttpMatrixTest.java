package com.soulsoftworks.sockbowlgame.config;

import com.google.gson.Gson;
import com.soulsoftworks.sockbowlgame.controller.api.AdminBanController;
import com.soulsoftworks.sockbowlgame.controller.api.AdminIpBanController;
import com.soulsoftworks.sockbowlgame.controller.api.AdminUsageController;
import com.soulsoftworks.sockbowlgame.controller.api.AuthController;
import com.soulsoftworks.sockbowlgame.controller.api.GameSessionController;
import com.soulsoftworks.sockbowlgame.controller.api.UserController;
import com.soulsoftworks.sockbowlgame.controller.exception.GlobalExceptionHandler;
import com.soulsoftworks.sockbowlgame.model.entity.BanRecord;
import com.soulsoftworks.sockbowlgame.model.entity.IpBan;
import com.soulsoftworks.sockbowlgame.model.entity.User;
import com.soulsoftworks.sockbowlgame.model.entity.UserStats;
import com.soulsoftworks.sockbowlgame.model.request.CreateGameRequest;
import com.soulsoftworks.sockbowlgame.model.response.GlobalUsage;
import com.soulsoftworks.sockbowlgame.model.response.JoinGameResponse;
import com.soulsoftworks.sockbowlgame.model.response.UsageCounter;
import com.soulsoftworks.sockbowlgame.model.state.GameMode;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.state.GameSettings;
import com.soulsoftworks.sockbowlgame.model.state.JoinStatus;
import com.soulsoftworks.sockbowlgame.service.BanService;
import com.soulsoftworks.sockbowlgame.service.SessionService;
import com.soulsoftworks.sockbowlgame.service.UserService;
import com.soulsoftworks.sockbowlgame.service.UserUsedQuestionService;
import com.soulsoftworks.sockbowlgame.ratelimit.ClientIpResolver;
import com.soulsoftworks.sockbowlgame.ratelimit.LimitSubjectResolver;
import com.soulsoftworks.sockbowlgame.service.authorization.GameAuthorizationPolicy;
import com.soulsoftworks.sockbowlgame.service.ban.IpBanService;
import com.soulsoftworks.sockbowlgame.usage.AdminUsageService;
import com.soulsoftworks.sockbowlgame.usage.HostedSessionQuota;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The game REST authorization matrix (plan m2-auth section 4.1) against the
 * real {@link SecurityConfig} filter chain and the real REST controllers, with
 * their services mocked. Every row is checked for five callers: anonymous, a
 * player, a moderator, an admin and the backend service-account token.
 *
 * <p>Also proves the perimeter is deny-by-default: removed routes
 * ({@code /api/v1/test}, {@code /api/v1/auth/login|success}, {@code /login},
 * {@code /oauth2/**}) and unmapped paths are 401 (anonymous) or 403
 * (authenticated), never 200, and nothing ever answers with a redirect.
 */
@WebMvcTest(properties = {"sockbowl.auth.enabled=true", "sockbowl.test.url-probes=true"})
@Import({SecurityConfig.class, GlobalExceptionHandler.class, GameAuthorizationPolicy.class,
        GameSessionController.class, AuthController.class, UserController.class, AdminBanController.class,
        AdminIpBanController.class, AdminUsageController.class,
        SecurityMatrixProbeController.class})
@ContextConfiguration(classes = SecurityConfigHttpMatrixTest.TestApp.class)
class SecurityConfigHttpMatrixTest {

    @SpringBootConfiguration
    static class TestApp {
    }

    static final String SERVICE_CLIENT = "sockbowl-game-backend";

    enum Caller { ANON, PLAYER, MODERATOR, ADMIN, SERVICE }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;
    @MockitoBean
    private SessionService sessionService;
    @MockitoBean
    private BanService banService;
    @MockitoBean
    private IpBanService ipBanService;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private UserUsedQuestionService usedQuestionService;
    @MockitoBean
    private HostedSessionQuota hostedSessionQuota;
    @MockitoBean
    private LimitSubjectResolver limitSubjectResolver;
    @MockitoBean
    private ClientIpResolver clientIpResolver;
    @MockitoBean
    private AdminUsageService adminUsageService;

    @BeforeEach
    void stubServices() {
        GameSession session = GameSession.builder().id("g1").joinCode("ABCDEF")
                .gameSettings(GameSettings.builder().build()).build();
        when(sessionService.createNewGame(any(), any())).thenReturn(session);
        JoinGameResponse joined = new JoinGameResponse();
        joined.setJoinStatus(JoinStatus.SUCCESS);
        joined.setGameSessionId("g1");
        when(sessionService.addPlayerToGameSessionWithJoinCode(any())).thenReturn(joined);
        when(sessionService.addAuthenticatedUserToGameSession(any(), any())).thenReturn(joined);

        User user = User.builder().id(UUID.randomUUID()).keycloakId("kc").name("n")
                .createdAt(Instant.now()).lastLoginAt(Instant.now()).build();
        when(userService.findOrCreateUser(anyString(), any(), any())).thenReturn(user);
        when(userService.getUserStats(any())).thenReturn(UserStats.builder().build());
        when(userService.getUserGameHistory(any(), any())).thenReturn(Page.empty());
        when(usedQuestionService.getUsedRemoteIds(any())).thenReturn(Set.of());

        when(banService.listActiveBans()).thenReturn(List.of());
        when(banService.createBan(anyString(), any(), any(), any())).thenReturn(
                BanRecord.builder().id(UUID.randomUUID()).bannedKeycloakId("x").reason("r").build());
        when(banService.removeBan(any())).thenReturn(true);
        when(ipBanService.listActive()).thenReturn(List.of());
        when(ipBanService.create(anyString(), any(), any(), any(), any())).thenReturn(
                IpBan.builder().id(UUID.randomUUID()).cidr("203.0.113.7/32").reason("r")
                        .createdAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build());
        when(ipBanService.remove(any())).thenReturn(true);

        when(adminUsageService.listUsers(any(), any(), any())).thenReturn(Page.empty());
        when(adminUsageService.getGlobal()).thenReturn(GlobalUsage.builder()
                .aiServerKey(UsageCounter.builder().metric("ai-generations").used(0).limit(200)
                        .kind(UsageCounter.KIND_GLOBAL_DAILY).overridden(false).build())
                .activeHostedSessions(0).topGuestIps(List.of()).rejectionsLastHour(0).build());
        when(adminUsageService.recentEvents(anyInt())).thenReturn(List.of());
        when(adminUsageService.getDetail(anyString(), any())).thenReturn(Optional.empty());
        when(adminUsageService.setQuotaOverride(anyString(), anyString(), any(), any())).thenReturn(
                UsageCounter.builder().metric("hosted-sessions").used(0).limit(1)
                        .kind(UsageCounter.KIND_CONCURRENT).overridden(true).build());

        when(jwtDecoder.decode(eq("not-a-valid-token"))).thenThrow(new BadJwtException("bad token"));
    }

    /* ------------------------------------------------------------------ */
    /* Callers                                                            */
    /* ------------------------------------------------------------------ */

    /** Authorities as the Keycloak converter emits them (composites expanded). */
    private static RequestPostProcessor as(Caller caller) {
        return switch (caller) {
            case ANON -> r -> r;
            case PLAYER -> user("kc-player", "player", "packet:read", "game:host");
            case MODERATOR -> user("kc-mod", "moderator", "player", "packet:read", "game:host", "user:ban");
            case ADMIN -> user("kc-admin", "admin", "author", "moderator", "player", "packet:read",
                    "packet:create", "packet:update", "packet:delete", "question:generate", "taxonomy:manage",
                    "game:host", "user:ban", "admin:access", "packet:manage-any");
            case SERVICE -> jwt().jwt(j -> j.subject("service-account-uuid")
                            .claim("azp", SERVICE_CLIENT)
                            .claim("realm_access", Map.of("roles", List.of("packet:read"))))
                    .authorities(new SimpleGrantedAuthority("packet:read"));
        };
    }

    private static RequestPostProcessor user(String sub, String... roles) {
        return jwt().jwt(j -> j.subject(sub)
                        .claim("azp", "sockbowl-game")
                        .claim("preferred_username", sub)
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(Arrays.stream(roles).map(SimpleGrantedAuthority::new).toArray(GrantedAuthority[]::new));
    }

    /* ------------------------------------------------------------------ */
    /* Matrix                                                             */
    /* ------------------------------------------------------------------ */

    private static final String CREATE_BODY = new Gson().toJson(
            CreateGameRequest.builder()
                    .gameSettings(GameSettings.builder()
                            .gameMode(GameMode.QUIZ_BOWL_CLASSIC)
                            .build())
                    .build());
    private static final String JOIN_BODY = "{\"joinCode\":\"ABCDEF\",\"name\":\"Guest\"}";
    private static final String BAN_BODY = "{\"bannedKeycloakId\":\"victim\",\"reason\":\"spam\"}";
    private static final String IP_BAN_BODY = "{\"cidr\":\"203.0.113.7/32\",\"reason\":\"spam\",\"ttlSeconds\":3600}";

    /**
     * One row per endpoint: method, path, body, then the expected status for
     * ANON, PLAYER, MODERATOR, ADMIN, SERVICE.
     */
    static Stream<Arguments> matrix() {
        String banPath = "/api/v1/admin/bans/" + UUID.randomUUID();
        String ipBanPath = "/api/v1/admin/bans/ip/" + UUID.randomUUID();
        String quotaPath = "/api/v1/admin/usage/some-sub/quota/hosted-sessions";
        String resetPath = "/api/v1/admin/usage/some-sub/reset";
        return Stream.of(
            // Guest endpoints (D1): anyone may host/join; a service token may not.
            row("POST", "/api/v1/session/create-new-game-session", CREATE_BODY, 200, 200, 200, 200, 403),
            row("POST", "/api/v1/session/join-game-session-by-code", JOIN_BODY, 200, 200, 200, 200, 403),
            // Authenticated join: users only.
            row("POST", "/api/v1/session/join-game-session-authenticated", JOIN_BODY, 401, 200, 200, 200, 403),
            // User endpoints: users only.
            row("GET", "/api/v1/user/profile", null, 401, 200, 200, 200, 403),
            row("GET", "/api/v1/user/stats", null, 401, 200, 200, 200, 403),
            row("GET", "/api/v1/user/history", null, 401, 200, 200, 200, 403),
            row("GET", "/api/v1/user/used-questions", null, 401, 200, 200, 200, 403),
            row("POST", "/api/v1/user/used-questions", "[\"q1\"]", 401, 200, 200, 200, 403),
            row("GET", "/api/v1/auth/me", null, 401, 200, 200, 200, 403),
            // Public status / health.
            row("GET", "/api/v1/auth/status", null, 200, 200, 200, 200, 200),
            row("GET", "/actuator/health", null, 200, 200, 200, 200, 200),
            // Ban management: user:ban (moderator, admin).
            row("GET", "/api/v1/admin/bans", null, 401, 403, 200, 200, 403),
            row("POST", "/api/v1/admin/bans", BAN_BODY, 401, 403, 201, 201, 403),
            row("DELETE", banPath, null, 401, 403, 204, 204, 403),
            // IP bans (M4, D8): user:ban as well.
            row("GET", "/api/v1/admin/bans/ip", null, 401, 403, 200, 200, 403),
            row("POST", "/api/v1/admin/bans/ip", IP_BAN_BODY, 401, 403, 201, 201, 403),
            row("DELETE", ipBanPath, null, 401, 403, 204, 204, 403),
            // Other admin pages: admin:access only.
            row("GET", "/api/v1/admin/console", null, 401, 403, 403, 200, 403),
            // Usage/quota admin view (M4, WP-G6): admin:access only.
            row("GET", "/api/v1/admin/usage", null, 401, 403, 403, 200, 403),
            row("GET", "/api/v1/admin/usage/global", null, 401, 403, 403, 200, 403),
            row("GET", "/api/v1/admin/usage/events", null, 401, 403, 403, 200, 403),
            row("GET", "/api/v1/admin/usage/some-sub", null, 401, 403, 403, 404, 403),
            row("PUT", quotaPath, "{\"limit\":5}", 401, 403, 403, 200, 403),
            row("POST", resetPath, null, 401, 403, 403, 204, 403),
            // Removed routes and unmapped paths: denied, never served.
            row("GET", "/api/v1/test", null, 401, 403, 403, 403, 403),
            row("GET", "/api/v1/auth/login", null, 401, 403, 403, 403, 403),
            row("GET", "/api/v1/auth/success", null, 401, 403, 403, 403, 403),
            row("GET", "/api/v1/auth/callback", null, 401, 403, 403, 403, 403),
            row("GET", "/login", null, 401, 403, 403, 403, 403),
            row("GET", "/oauth2/authorization/keycloak", null, 401, 403, 403, 403, 403),
            row("GET", "/login/oauth2/code/keycloak", null, 401, 403, 403, 403, 403),
            row("GET", "/api/v1/unmapped", null, 401, 403, 403, 403, 403),
            row("POST", "/api/v1/session/something-else", "{}", 401, 403, 403, 403, 403),
            row("GET", "/api/v1/session/create-new-game-session", null, 401, 403, 403, 403, 403),
            row("GET", "/", null, 401, 403, 403, 403, 403)
        );
    }

    private static Arguments row(String method, String path, String body, int... expected) {
        return Arguments.of(method, path, body, expected);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("matrix")
    void matrixRow(String method, String path, String body, int[] expected) throws Exception {
        Caller[] callers = Caller.values();
        for (int i = 0; i < callers.length; i++) {
            MockHttpServletRequestBuilder req = request(HttpMethod.valueOf(method), path).with(as(callers[i]));
            if (body != null) {
                req.contentType(MediaType.APPLICATION_JSON).content(body);
            }
            MvcResult result = mvc.perform(req).andReturn();
            int actual = result.getResponse().getStatus();
            assertThat(actual)
                    .as("%s %s as %s", method, path, callers[i])
                    .isEqualTo(expected[i]);
            assertThat(actual).as("no redirects: %s %s as %s", method, path, callers[i])
                    .isNotIn(301, 302, 303, 307, 308);
            assertThat(result.getResponse().getHeader("Location")).isNull();
        }
    }

    /* ------------------------------------------------------------------ */
    /* Specific properties                                                */
    /* ------------------------------------------------------------------ */

    @Test
    void invalidBearerOnGuestEndpointIs401() throws Exception {
        mvc.perform(request(HttpMethod.POST, "/api/v1/session/create-new-game-session")
                        .header("Authorization", "Bearer not-a-valid-token")
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"));
        mvc.perform(request(HttpMethod.POST, "/api/v1/session/join-game-session-by-code")
                        .header("Authorization", "Bearer not-a-valid-token")
                        .contentType(MediaType.APPLICATION_JSON).content(JOIN_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticatedIsJson401WithBearerChallenge() throws Exception {
        MvcResult result = mvc.perform(request(HttpMethod.GET, "/api/v1/user/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", startsWith("Bearer")))
                .andReturn();
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(result.getResponse().getContentAsString()).contains("\"error\":\"Unauthorized\"");
    }

    @Test
    void forbiddenIsJson403() throws Exception {
        MvcResult result = mvc.perform(request(HttpMethod.GET, "/api/v1/admin/bans").with(as(Caller.PLAYER)))
                .andExpect(status().isForbidden())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("\"error\":\"Forbidden\"");
    }

    @Test
    void websocketHandshakePathIsNotBlockedBySecurity() throws Exception {
        // No STOMP endpoint in this slice, so the handshake path 404s, but it
        // must get past the filter chain (auth happens at STOMP CONNECT).
        int statusCode = mvc.perform(request(HttpMethod.GET, "/sockbowl-game/info"))
                .andReturn().getResponse().getStatus();
        assertThat(statusCode).isNotIn(401, 403);
    }

    @Test
    void guestJoinStillRequiresName() throws Exception {
        mvc.perform(request(HttpMethod.POST, "/api/v1/session/join-game-session-by-code")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"joinCode\":\"ABCDEF\"}"))
                .andExpect(status().isBadRequest());
    }

    /* ------------------------------------------------------------------ */
    /* FIX-G2 (defect G-05): exercise the real converter, not a stubbed authority */
    /* ------------------------------------------------------------------ */

    /**
     * Every row in {@link #matrix()} authenticates with
     * {@code jwt().authorities(...)}: the {@code RequestPostProcessor} hands
     * Spring Security a pre-built authority list directly, so those rows
     * never invoke {@link SecurityConfig#keycloakJwtAuthenticationConverter()}.
     * A bug there (wrong claim name, wrong role prefix, roles dropped) would
     * pass every row above and still deny or admit the wrong caller in
     * production, where the converter is the only thing deriving authorities.
     *
     * <p>These rows instead stub only {@link #jwtDecoder} to decode a bearer
     * value into a {@link Jwt} carrying a {@code realm_access.roles} claim,
     * then send a plain {@code Authorization: Bearer} header with no
     * {@code jwt()} post-processor at all, exactly like
     * {@link #invalidBearerOnGuestEndpointIs401()} already does for the
     * failure path. The real {@code securityFilterChain} decodes it via the
     * mock and runs the *real* {@code keycloakJwtAuthenticationConverter}
     * bean to derive authorities, so a converter bug shows up here even
     * though every {@code matrix()} row would still pass.
     */
    static Stream<Arguments> realConverterMatrix() {
        return Stream.of(
                // PLAYER: authenticated, but lacks user:ban -> converter must
                // NOT grant it just because "player" appears in realm_access.
                Arguments.of("player", "kc-player-rc", "sockbowl-game",
                        new String[]{"player", "packet:read", "game:host"},
                        "GET", "/api/v1/admin/bans", 403),
                // MODERATOR: converter must grant the raw "user:ban" realm
                // role name (not just a ROLE_-prefixed authority) for
                // hasAuthority("user:ban") to pass.
                Arguments.of("moderator", "kc-mod-rc", "sockbowl-game",
                        new String[]{"moderator", "player", "packet:read", "game:host", "user:ban"},
                        "GET", "/api/v1/admin/bans", 200),
                // ADMIN: converter must grant "admin:access" from realm_access.
                Arguments.of("admin", "kc-admin-rc", "sockbowl-game",
                        new String[]{"admin", "admin:access", "user:ban", "player", "packet:read", "game:host"},
                        "GET", "/api/v1/admin/console", 200),
                // SERVICE: a client-credentials token (azp = the backend
                // client id) must still be denied a user-only endpoint even
                // though the converter grants it "packet:read"; this is
                // AuthenticatedUser.isServiceToken reading the same azp claim
                // the mocked Jwt carries here, through the real chain.
                Arguments.of("service", "service-account-rc", SERVICE_CLIENT,
                        new String[]{"packet:read"},
                        "GET", "/api/v1/user/profile", 403)
        );
    }

    @ParameterizedTest(name = "[real converter] {0}: {4} {5}")
    @MethodSource("realConverterMatrix")
    void matrixRowThroughRealJwtDecoderAndConverter(String roleLabel, String sub, String azp, String[] realmRoles,
                                                     String method, String path, int expected) throws Exception {
        String token = "rc-token-" + sub;
        Jwt jwt = Jwt.withTokenValue(token)
                .header("alg", "none")
                .subject(sub)
                .claim("azp", azp)
                .claim("preferred_username", sub)
                .claim("realm_access", Map.of("roles", List.of(realmRoles)))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(jwtDecoder.decode(token)).thenReturn(jwt);

        MvcResult result = mvc.perform(request(HttpMethod.valueOf(method), path)
                        .header("Authorization", "Bearer " + token))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("%s %s as %s (realm_access=%s, azp=%s) through the real converter",
                        method, path, roleLabel, List.of(realmRoles), azp)
                .isEqualTo(expected);
    }
}
