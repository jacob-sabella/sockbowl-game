package com.soulsoftworks.sockbowlgame.service;

import com.soulsoftworks.sockbowlgame.model.request.CreateGameRequest;
import com.soulsoftworks.sockbowlgame.model.request.JoinGameRequest;
import com.soulsoftworks.sockbowlgame.model.response.JoinGameResponse;
import com.soulsoftworks.sockbowlgame.model.state.*;
import com.soulsoftworks.sockbowlgame.repository.GameSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Session-creation and joining behavior for the FREE_FOR_ALL game mode. */
class SessionServiceTest {

    private GameSessionRepository gameSessionRepository;
    private SessionService sessionService;

    @BeforeEach
    void setup() {
        gameSessionRepository = mock(GameSessionRepository.class);
        sessionService = new SessionService(gameSessionRepository);
        when(gameSessionRepository.findGameSessionByJoinCode(any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("createNewGame for FREE_FOR_ALL starts with an empty teamList (no pre-created teams)")
    void ffaCreateNewGameHasNoPreCreatedTeams() {
        GameSettings settings = new GameSettings();
        settings.setGameMode(GameMode.FREE_FOR_ALL);
        CreateGameRequest request = CreateGameRequest.builder().gameSettings(settings).build();

        GameSession session = sessionService.createNewGame(request);

        assertTrue(session.getTeamList().isEmpty());
    }

    @Test
    @DisplayName("Joining a FREE_FOR_ALL game auto-creates a one-player team named after the joining player")
    void ffaJoinAutoCreatesOnePlayerTeamNamedAfterJoiner() {
        GameSettings settings = new GameSettings();
        settings.setGameMode(GameMode.FREE_FOR_ALL);
        CreateGameRequest request = CreateGameRequest.builder().gameSettings(settings).build();

        GameSession session = sessionService.createNewGame(request);
        when(gameSessionRepository.findGameSessionByJoinCode(session.getJoinCode()))
                .thenReturn(Optional.of(session));

        JoinGameRequest joinRequest = JoinGameRequest.builder()
                .joinCode(session.getJoinCode())
                .name("Jacob")
                .build();

        JoinGameResponse response = sessionService.addPlayerToGameSessionWithJoinCode(joinRequest);

        assertEquals(JoinStatus.SUCCESS, response.getJoinStatus());
        assertEquals(1, session.getTeamList().size());

        Team team = session.getTeamList().get(0);
        assertEquals("Jacob", team.getTeamName());
        assertEquals(1, team.getTeamPlayers().size());
        assertEquals(PlayerMode.BUZZER, team.getTeamPlayers().get(0).getPlayerMode());
    }

    @Test
    @DisplayName("Each FREE_FOR_ALL joiner gets their own separate team")
    void ffaEachJoinerGetsOwnTeam() {
        GameSettings settings = new GameSettings();
        settings.setGameMode(GameMode.FREE_FOR_ALL);
        CreateGameRequest request = CreateGameRequest.builder().gameSettings(settings).build();

        GameSession session = sessionService.createNewGame(request);
        when(gameSessionRepository.findGameSessionByJoinCode(session.getJoinCode()))
                .thenReturn(Optional.of(session));

        sessionService.addPlayerToGameSessionWithJoinCode(JoinGameRequest.builder()
                .joinCode(session.getJoinCode()).name("Alice").build());
        sessionService.addPlayerToGameSessionWithJoinCode(JoinGameRequest.builder()
                .joinCode(session.getJoinCode()).name("Bob").build());

        assertEquals(2, session.getTeamList().size());
        assertEquals("Alice", session.getTeamList().get(0).getTeamName());
        assertEquals("Bob", session.getTeamList().get(1).getTeamName());
    }

    @Test
    @DisplayName("Blank-name FREE_FOR_ALL joiners get distinct seat-numbered team names")
    void ffaBlankNameJoinersGetDistinctSeatNumberedTeams() {
        GameSettings settings = new GameSettings();
        settings.setGameMode(GameMode.FREE_FOR_ALL);
        CreateGameRequest request = CreateGameRequest.builder().gameSettings(settings).build();

        GameSession session = sessionService.createNewGame(request);
        when(gameSessionRepository.findGameSessionByJoinCode(session.getJoinCode()))
                .thenReturn(Optional.of(session));

        // One null-name joiner, one blank-name joiner — neither should collapse
        // into an indistinguishable "Player" team.
        sessionService.addPlayerToGameSessionWithJoinCode(JoinGameRequest.builder()
                .joinCode(session.getJoinCode()).build());
        sessionService.addPlayerToGameSessionWithJoinCode(JoinGameRequest.builder()
                .joinCode(session.getJoinCode()).name("   ").build());

        assertEquals(2, session.getTeamList().size());
        assertEquals("Player 1", session.getTeamList().get(0).getTeamName());
        assertEquals("Player 2", session.getTeamList().get(1).getTeamName());
        assertNotEquals(session.getTeamList().get(0).getTeamName(),
                session.getTeamList().get(1).getTeamName());
    }
}
