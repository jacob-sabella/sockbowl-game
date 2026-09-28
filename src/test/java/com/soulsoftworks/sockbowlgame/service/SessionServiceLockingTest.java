package com.soulsoftworks.sockbowlgame.service;

import com.soulsoftworks.sockbowlgame.model.entity.User;
import com.soulsoftworks.sockbowlgame.model.request.JoinGameRequest;
import com.soulsoftworks.sockbowlgame.model.response.JoinGameResponse;
import com.soulsoftworks.sockbowlgame.model.state.GameMode;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.state.GameSettings;
import com.soulsoftworks.sockbowlgame.model.state.JoinStatus;
import com.soulsoftworks.sockbowlgame.model.state.Team;
import com.soulsoftworks.sockbowlgame.repository.GameSessionRepository;
import com.soulsoftworks.sockbowlgame.repository.UserGameHistoryRepository;
import com.soulsoftworks.sockbowlgame.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Both join paths (guest and authenticated) must load, mutate and save the
 * session while holding its {@link GameSessionLocks} lock, re-reading it
 * after the lock is taken (M2R2-LIVE-01). The test holds the lock itself and
 * checks that a join parks on it without having saved, then completes once
 * the lock is released. {@link SessionLostUpdateIT} covers the same race end
 * to end against Redis.
 */
class SessionServiceLockingTest {

    private static final String ID = "LOCK-SESSION";
    private static final String CODE = "LOCKCD";

    private GameSessionRepository sessions;
    private SessionService service;
    private GameSession session;

    @BeforeEach
    void setup() {
        sessions = mock(GameSessionRepository.class);
        service = new SessionService(sessions);

        UserRepository users = mock(UserRepository.class);
        ReflectionTestUtils.setField(service, "userRepository", users);
        ReflectionTestUtils.setField(service, "userGameHistoryRepository", mock(UserGameHistoryRepository.class));
        when(users.findByKeycloakId(any())).thenReturn(Optional.empty());
        when(users.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) {
                u.setId(UUID.randomUUID());
            }
            return u;
        });

        GameSettings settings = new GameSettings();
        settings.setGameMode(GameMode.QUIZ_BOWL_CLASSIC);
        session = GameSession.builder().id(ID).joinCode(CODE).gameSettings(settings).build();
        session.getTeamList().add(new Team());
        session.getTeamList().add(new Team());
        when(sessions.findGameSessionByJoinCode(CODE)).thenReturn(Optional.of(session));
        when(sessions.findById(ID)).thenReturn(Optional.of(session));
    }

    @Test
    @DisplayName("A guest join waits for the session lock and re-reads the session under it")
    void guestJoinSerializesOnSessionLock() throws Exception {
        assertJoinSerialized(() -> service.addPlayerToGameSessionWithJoinCode(
                JoinGameRequest.builder().joinCode(CODE).name("guest").build()));
    }

    @Test
    @DisplayName("An authenticated join waits for the session lock and re-reads the session under it")
    void authenticatedJoinSerializesOnSessionLock() throws Exception {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("kc-user")
                .claim("preferred_username", "user")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        assertJoinSerialized(() -> service.addAuthenticatedUserToGameSession(
                JoinGameRequest.builder().joinCode(CODE).name("user").build(), jwt));
    }

    private void assertJoinSerialized(Supplier<JoinGameResponse> join) throws Exception {
        AtomicReference<JoinGameResponse> response = new AtomicReference<>();
        Thread joiner = new Thread(() -> response.set(join.get()), "joiner");

        GameSessionLocks.withLock(ID, () -> {
            joiner.start();
            await().atMost(10, TimeUnit.SECONDS).pollInterval(5, TimeUnit.MILLISECONDS)
                    .until(() -> GameSessionLocks.isQueued(ID, joiner));
            // Parked on the lock: it has only resolved the join code (to learn
            // the id), and has neither re-read nor saved the session.
            verify(sessions, times(1)).findGameSessionByJoinCode(CODE);
            verify(sessions, never()).findById(any());
            verify(sessions, never()).save(any());
        });

        joiner.join(TimeUnit.SECONDS.toMillis(10));
        assertThat(joiner.isAlive()).isFalse();
        assertThat(response.get().getJoinStatus()).isEqualTo(JoinStatus.SUCCESS);
        // One join-code search, before the lock; the re-read under the lock
        // is by id, so no search (and its global save-blocking lock) runs
        // while the session lock is held (R3-G-LOCK).
        verify(sessions, times(1)).findGameSessionByJoinCode(CODE);
        verify(sessions, times(1)).findById(ID);
        verify(sessions, times(1)).save(session);
        assertThat(session.getPlayerById(response.get().getPlayerSessionId())).isNotNull();
    }
}
