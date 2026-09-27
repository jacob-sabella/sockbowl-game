package com.soulsoftworks.sockbowlgame.security.stomp;

import com.soulsoftworks.sockbowlgame.controller.helper.StompTestClient;
import com.soulsoftworks.sockbowlgame.model.entity.User;
import com.soulsoftworks.sockbowlgame.model.entity.UserGameHistory;
import com.soulsoftworks.sockbowlgame.model.request.JoinGameRequest;
import com.soulsoftworks.sockbowlgame.model.response.JoinGameResponse;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.ratelimit.ShippedLimitProperties;
import com.soulsoftworks.sockbowlgame.repository.IpBanRepository;
import com.soulsoftworks.sockbowlgame.repository.UserGameHistoryRepository;
import com.soulsoftworks.sockbowlgame.repository.UserRepository;
import com.soulsoftworks.sockbowlgame.service.BanService;
import com.soulsoftworks.sockbowlgame.service.UserService;
import com.soulsoftworks.sockbowlgame.service.UserUsedQuestionService;
import com.soulsoftworks.sockbowlgame.util.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The STOMP limiter is a <b>pre-auth</b> guard (plan m4-limits WP-G3; M4-RL-06):
 * with auth on, a CONNECT flood from one address is refused with
 * {@code RATE_LIMITED} (policy {@code ws-connect}) before the bearer token is
 * decoded, so a flood cannot buy JWT verification work.
 */
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
        "sockbowl.quota.enabled=false"
})
@Import(StompGuardOrderingIT.ClockConfig.class)
class StompGuardOrderingIT extends StompSecurityITSupport {

    static final MutableClock CLOCK = MutableClock.startingNow();

    @DynamicPropertySource
    static void limits(DynamicPropertyRegistry registry) {
        ShippedLimitProperties.registerRateLimits(registry);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        Clock testClock() {
            return CLOCK;
        }
    }

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
    @MockitoBean
    IpBanRepository ipBanRepository;

    private GameSession game;
    private JoinGameResponse alice;

    private static Jwt alice() {
        Instant expiresAt = Instant.now().plusSeconds(300);
        return Jwt.withTokenValue("tok-alice")
                .header("alg", "RS256")
                .subject("kc-alice")
                .claim("azp", "sockbowl-game")
                .claim("preferred_username", "kc-alice")
                .claim("realm_access", Map.of("roles", List.of("game:host")))
                .audience(List.of("sockbowl-api"))
                .issuedAt(expiresAt.minusSeconds(600))
                .expiresAt(expiresAt)
                .build();
    }

    @BeforeEach
    void seat() {
        CLOCK.advance(Duration.ofMinutes(2));
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
        when(jwtDecoder.decode("tok-alice")).thenReturn(alice());
        game = newGame();
        alice = sessionService.addAuthenticatedUserToGameSession(
                JoinGameRequest.builder().joinCode(game.getJoinCode()).name("Alice").build(), alice());
    }

    private StompTestClient.Connection connectAlice() {
        return connect(game.getId(), alice.getPlayerSessionId(), null, "tok-alice");
    }

    @Test
    void connectFloodIsRefusedBeforeTheJwtIsDecoded() throws Exception {
        for (int i = 1; i <= 10; i++) {
            StompTestClient.Connection c = connectAlice();
            c.awaitConnected();
            c.disconnect();
        }
        verify(jwtDecoder, atLeastOnce()).decode("tok-alice");
        clearInvocations(jwtDecoder);

        for (int i = 0; i < 3; i++) {
            StompTestClient.Connection refused = connectAlice();
            assertFatal(refused, StompErrorCode.RATE_LIMITED);
            assertThat(json(refused.awaitError().body()).get("policy").getAsString())
                    .isEqualTo(StompRateLimitGuard.WS_CONNECT);
        }
        verify(jwtDecoder, never()).decode(anyString());

        CLOCK.advance(Duration.ofMinutes(1));
        connectAlice().awaitConnected();
        verify(jwtDecoder, atLeastOnce()).decode("tok-alice");
    }
}
