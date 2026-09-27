package com.soulsoftworks.sockbowlgame.controller.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.redis.testcontainers.RedisContainer;
import com.soulsoftworks.sockbowlgame.model.entity.User;
import com.soulsoftworks.sockbowlgame.model.entity.UserGameHistory;
import com.soulsoftworks.sockbowlgame.model.request.CreateGameRequest;
import com.soulsoftworks.sockbowlgame.model.state.GameMode;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.state.GameSettings;
import com.soulsoftworks.sockbowlgame.model.state.Player;
import com.soulsoftworks.sockbowlgame.model.state.ProctorType;
import com.soulsoftworks.sockbowlgame.repository.IpBanRepository;
import com.soulsoftworks.sockbowlgame.repository.UserGameHistoryRepository;
import com.soulsoftworks.sockbowlgame.repository.UserRepository;
import com.soulsoftworks.sockbowlgame.service.BanService;
import com.soulsoftworks.sockbowlgame.service.SessionService;
import com.soulsoftworks.sockbowlgame.service.UserService;
import com.soulsoftworks.sockbowlgame.service.UserUsedQuestionService;
import com.soulsoftworks.sockbowlgame.util.TestcontainersUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Game hosting and joining rules with {@code sockbowl.auth.enabled=true}
 * (plan m2-auth section 2.6, decision D1, AUTH-04/11/17/19), through the full
 * application context: the real {@link SessionService} against a Redis
 * Testcontainer, the real security chain, and the real authorization policy.
 * Only the JPA-backed collaborators ({@link BanService}, the user repositories)
 * and the {@link JwtDecoder} are mocked; the {@code jwt()} post-processor
 * supplies the validated token.
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
        "sockbowl.questions.url=http://127.0.0.1:1/"
})
@AutoConfigureMockMvc
class GameSessionControllerAuthIT {

    @Container
    private static final RedisContainer REDIS = TestcontainersUtil.getRedisContainer();

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("sockbowl.redis.game-cache.hostname", REDIS::getHost);
        registry.add("sockbowl.redis.game-cache.port", () -> REDIS.getMappedPort(6379).toString());
    }

    private static final String CREATE = "/api/v1/session/create-new-game-session";
    private static final String JOIN_BY_CODE = "/api/v1/session/join-game-session-by-code";
    private static final String JOIN_AUTH = "/api/v1/session/join-game-session-authenticated";

    private final Gson gson = new Gson();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SessionService sessionService;

    @LocalServerPort
    private int port;

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
    // M4 IP bans (JPA is off in this context, so the repository is mocked too).
    @MockitoBean
    private IpBanRepository ipBanRepository;

    @BeforeEach
    void stubs() {
        when(banService.isBanned(anyString())).thenReturn(false);
        when(banService.isBanned("kc-banned")).thenReturn(true);
        when(userRepository.findByKeycloakId(anyString())).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) {
                u.setId(UUID.randomUUID());
            }
            return u;
        });
        when(userGameHistoryRepository.save(any(UserGameHistory.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jwtDecoder.decode(eq("garbage"))).thenThrow(new BadJwtException("bad"));
    }

    /* ------------------------------------------------------------------ */
    /* Callers                                                            */
    /* ------------------------------------------------------------------ */

    private static RequestPostProcessor userToken(String sub, String... roles) {
        return jwt().jwt(j -> j.subject(sub)
                        .claim("azp", "sockbowl-game")
                        .claim("preferred_username", sub)
                        .claim("name", "Name " + sub)
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(Arrays.stream(roles).map(SimpleGrantedAuthority::new)
                        .toArray(GrantedAuthority[]::new));
    }

    private static RequestPostProcessor host(String sub) {
        return userToken(sub, "player", "packet:read", "game:host");
    }

    private static RequestPostProcessor serviceToken() {
        return jwt().jwt(j -> j.subject("svc-account")
                        .claim("azp", "sockbowl-game-backend")
                        .claim("realm_access", Map.of("roles", List.of("packet:read"))))
                .authorities(new SimpleGrantedAuthority("packet:read"));
    }

    private static final RequestPostProcessor ANON = r -> r;

    private String createBody() {
        return gson.toJson(CreateGameRequest.builder()
                .gameSettings(GameSettings.builder()
                        .gameMode(GameMode.QUIZ_BOWL_CLASSIC)
                        .proctorType(ProctorType.ONLINE_PROCTOR)
                        .build())
                .build());
    }

    private String joinBody(String code, String name) {
        JsonObject o = new JsonObject();
        o.addProperty("joinCode", code);
        if (name != null) {
            o.addProperty("name", name);
        }
        return o.toString();
    }

    private MvcResult create(RequestPostProcessor caller, int expected) throws Exception {
        return mvc.perform(post(CREATE).with(caller)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().is(expected))
                .andReturn();
    }

    private GameSession createGuestGame() throws Exception {
        MvcResult r = create(ANON, 200);
        String id = gson.fromJson(r.getResponse().getContentAsString(), JsonObject.class).get("id").getAsString();
        return sessionService.getGameSessionById(id);
    }

    private MvcResult send(String path, RequestPostProcessor caller, String body, int expected) throws Exception {
        return mvc.perform(post(path)
                        .with(caller).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected))
                .andReturn();
    }

    /* ------------------------------------------------------------------ */
    /* create-new-game-session                                            */
    /* ------------------------------------------------------------------ */

    @Test
    void guestCreatesUnownedSession() throws Exception {
        GameSession session = createGuestGame();
        assertThat(session).isNotNull();
        assertThat(session.getGameOwnerId()).isNull();
    }

    @Test
    void gameHostUserCreatesSessionOwnedBySubject() throws Exception {
        MvcResult r = create(host("kc-alice"), 200);
        String id = gson.fromJson(r.getResponse().getContentAsString(), JsonObject.class).get("id").getAsString();
        assertThat(sessionService.getGameSessionById(id).getGameOwnerId()).isEqualTo("kc-alice");
    }

    @Test
    void userWithoutGameHostCannotCreate() throws Exception {
        create(userToken("kc-nohost", "packet:read"), 403);
    }

    @Test
    void bannedUserCannotCreate() throws Exception {
        create(host("kc-banned"), 403);
    }

    @Test
    void serviceTokenCannotCreate() throws Exception {
        create(serviceToken(), 403);
    }

    @Test
    void invalidBearerCannotCreate() throws Exception {
        mvc.perform(post(CREATE)
                        .header("Authorization", "Bearer garbage")
                        .contentType(MediaType.APPLICATION_JSON).content(createBody()))
                .andExpect(status().isUnauthorized());
    }

    /* ------------------------------------------------------------------ */
    /* join-game-session-by-code                                          */
    /* ------------------------------------------------------------------ */

    @Test
    void guestJoinByCodeIsAGuest() throws Exception {
        GameSession session = createGuestGame();
        send(JOIN_BY_CODE, ANON, joinBody(session.getJoinCode(), "Guesty"), 200);

        Player p = sessionService.getGameSessionById(session.getId()).getPlayerList().get(0);
        assertThat(p.getKeycloakId()).isNull();
        assertThat(p.getName()).isEqualTo("Guesty");
    }

    @Test
    void joinByCodeUnknownCodeIs404() throws Exception {
        send(JOIN_BY_CODE, ANON, joinBody("NOPE99", "Guesty"), 404);
        send(JOIN_BY_CODE, host("kc-bob"), joinBody("NOPE99", null), 404);
        verify(userRepository, never()).save(any());
    }

    @Test
    void joinByCodeWithBearerTakesTheAuthenticatedPath() throws Exception {
        GameSession session = createGuestGame();
        MvcResult r = send(JOIN_BY_CODE, host("kc-bob"), joinBody(session.getJoinCode(), null), 200);

        JsonObject body = gson.fromJson(r.getResponse().getContentAsString(), JsonObject.class);
        assertThat(body.get("userId")).isNotNull();

        Player p = sessionService.getGameSessionById(session.getId()).getPlayerList().get(0);
        assertThat(p.getKeycloakId()).isEqualTo("kc-bob");
        assertThat(p.isGuest()).isFalse();
    }

    @Test
    void joinByCodeWithBannedBearerIs403() throws Exception {
        GameSession session = createGuestGame();
        send(JOIN_BY_CODE, host("kc-banned"), joinBody(session.getJoinCode(), "Sneaky"), 403);
        assertThat(sessionService.getGameSessionById(session.getId()).getPlayerList()).isEmpty();
    }

    @Test
    void joinByCodeWithServiceTokenIs403() throws Exception {
        GameSession session = createGuestGame();
        send(JOIN_BY_CODE, serviceToken(), joinBody(session.getJoinCode(), "svc"), 403);
    }

    /* ------------------------------------------------------------------ */
    /* join-game-session-authenticated                                    */
    /* ------------------------------------------------------------------ */

    @Test
    void authenticatedJoinRequiresAToken() throws Exception {
        GameSession session = createGuestGame();
        send(JOIN_AUTH, ANON, joinBody(session.getJoinCode(), null), 401);
    }

    @Test
    void authenticatedJoinRejectsBannedUser() throws Exception {
        GameSession session = createGuestGame();
        send(JOIN_AUTH, host("kc-banned"), joinBody(session.getJoinCode(), null), 403);
    }

    @Test
    void authenticatedJoinRejectsServiceToken() throws Exception {
        GameSession session = createGuestGame();
        send(JOIN_AUTH, serviceToken(), joinBody(session.getJoinCode(), null), 403);
    }

    @Test
    void authenticatedJoinUnknownCodeIs404() throws Exception {
        send(JOIN_AUTH, host("kc-bob"), joinBody("NOPE99", null), 404);
        verify(userRepository, never()).save(any());
    }

    @Test
    void authenticatedJoinBindsIdentity() throws Exception {
        GameSession session = createGuestGame();
        send(JOIN_AUTH, host("kc-carol"), joinBody(session.getJoinCode(), null), 200);
        Player p = sessionService.getGameSessionById(session.getId()).getPlayerList().get(0);
        assertThat(p.getKeycloakId()).isEqualTo("kc-carol");
    }

    /* ------------------------------------------------------------------ */
    /* Over real HTTP (servlet container error dispatch, not MockMvc)      */
    /* ------------------------------------------------------------------ */

    @Test
    void unknownCodeIs404OverRealHttpNot401() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + JOIN_BY_CODE))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(joinBody("NOPE99", "Guesty")))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("Game not found");
    }

    @Test
    void unmappedPathIs401OverRealHttpWithoutRedirect() throws Exception {
        HttpResponse<String> response = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).build().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }
}
