package com.soulsoftworks.sockbowlgame.security.stomp;

import com.soulsoftworks.sockbowlgame.controller.resolver.GameSessionInjectionResolver;
import com.soulsoftworks.sockbowlgame.model.request.GameSessionInjection;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.state.GameSettings;
import com.soulsoftworks.sockbowlgame.model.state.Player;
import com.soulsoftworks.sockbowlgame.repository.BanRepository;
import com.soulsoftworks.sockbowlgame.service.BanService;
import com.soulsoftworks.sockbowlgame.service.SessionService;
import com.soulsoftworks.sockbowlgame.service.authorization.GameAuthorizationPolicy;
import com.soulsoftworks.sockbowlgame.service.ban.BanRedisMirror;
import com.soulsoftworks.sockbowlgame.util.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static com.soulsoftworks.sockbowlgame.security.stomp.StompTestFrames.frame;
import static com.soulsoftworks.sockbowlgame.security.stomp.StompTestFrames.message;
import static com.soulsoftworks.sockbowlgame.security.stomp.StompTestFrames.withUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Per-message cost of a signed-in player's SEND (plan m4-limits WP-G3,
 * M4-RL-06): after CONNECT the JWT is never decoded again, and the per-message
 * ban re-check in {@link GameSessionInjectionResolver} is answered from
 * {@code BanStatusCache} (at most one Redis read per TTL, never Postgres).
 */
class GameSessionInjectionResolverCostTest {

    private static final String GAME = "game-1";
    private static final String ALICE = "p-alice";

    private TestJwtIssuer issuer;
    private JwtDecoder decoder;
    private BanRepository banRepository;
    private BanRedisMirror mirror;
    private StompInboundInterceptor interceptor;
    private GameSessionInjectionResolver resolver;
    private final MessageChannel channel = mock(MessageChannel.class);

    @BeforeEach
    void setUp() throws Exception {
        issuer = new TestJwtIssuer();
        decoder = mock(JwtDecoder.class, delegatesTo(issuer.decoder()));
        banRepository = mock(BanRepository.class);
        mirror = mock(BanRedisMirror.class);
        when(mirror.read(anyString())).thenReturn(Optional.empty());

        SessionService sessionService = mock(SessionService.class);
        GameSession session = GameSession.builder().id(GAME).joinCode("ABCDEF").gameSettings(new GameSettings()).build();
        session.getPlayerList().add(Player.builder().playerId(ALICE).playerSecret("alice-secret")
                .keycloakId("kc-alice").isGuest(false).build());
        when(sessionService.getGameSessionById(GAME)).thenReturn(session);

        BanService banService = new BanService(banRepository, mirror, MutableClock.startingNow(), Duration.ofSeconds(30));
        GameAuthorizationPolicy policy = new GameAuthorizationPolicy(true, banService);
        StompConnectAuthenticator authenticator = new StompConnectAuthenticator(sessionService, policy,
                StompConnectAuthenticatorTest.provider(decoder));
        interceptor = new StompInboundInterceptor(authenticator,
                List.of(new StompDestinationGuard(policy, StompConnectAuthenticatorTest.provider(decoder))));
        resolver = new GameSessionInjectionResolver(sessionService, policy);
    }

    @AfterEach
    void tearDown() throws Exception {
        issuer.close();
    }

    @Test
    void hundredSendsCostNoJwtDecodeAndNoPostgresBanQuery() {
        String token = issuer.user("kc-alice", "game:host");
        Message<?> connected = interceptor.preSend(message(frame(StompCommand.CONNECT, null,
                "gameSessionId", GAME, "playerSessionId", ALICE, "Authorization", "Bearer " + token)), channel);
        Principal principal = MessageHeaderAccessor.getAccessor(connected, StompHeaderAccessor.class).getUser();
        assertThat(principal).isInstanceOf(StompPrincipal.class);
        verify(decoder, times(1)).decode(anyString());
        clearInvocations(decoder);

        for (int i = 0; i < 100; i++) {
            Message<?> send = interceptor.preSend(
                    message(withUser(frame(StompCommand.SEND, "/app/game/player-incoming-buzz"), principal)), channel);
            assertThat(send).as("SEND #%d passes", i).isNotNull();
            GameSessionInjection injection = (GameSessionInjection) resolver.resolveArgument(null, send);
            assertThat(injection.getGameSession().getId()).isEqualTo(GAME);
        }

        verify(decoder, never()).decode(anyString());
        verify(mirror, atMost(1)).read("kc-alice");
        verifyNoInteractions(banRepository);
    }
}
