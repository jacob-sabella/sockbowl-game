package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.model.game.socket.in.config.UpdatePlayerTeamMessage;
import com.soulsoftworks.sockbowlgame.model.game.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.game.socket.out.config.PlayerRosterUpdate;
import com.soulsoftworks.sockbowlgame.model.game.socket.out.error.ProcessErrorMessage;
import com.soulsoftworks.sockbowlgame.model.game.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.game.state.GameSettings;
import com.soulsoftworks.sockbowlgame.model.game.state.Player;
import com.soulsoftworks.sockbowlgame.model.game.state.Team;
import com.soulsoftworks.sockbowlgame.service.GameSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


public class GameConfigurationMessageProcessorTest {

    private GameConfigurationMessageProcessor processor;
    @Mock
    private GameSessionService gameSessionService;

    private GameSession mockGameSession;

    @Nested
    class TeamTests {

        private List<Player> playerList = createPlayers(2);

        @BeforeEach
        void setup() {
            MockitoAnnotations.openMocks(this);
            processor = new GameConfigurationMessageProcessor();

            mockGameSession = GameSession.builder()
                    .id("TEST")
                    .gameSettings(new GameSettings())
                    .joinCode("TEST")
                    .teams(createTeams(2))
                    .playerList(playerList)
                    .build();
        }

        @Test
        @DisplayName("Player without permission to update a team causes error")
        void changeTeamForTargetPlayer_PlayerDoesNotHavePermissionToUpdateTeam_ReturnsProcessErrorMessage() {

            UpdatePlayerTeamMessage message = UpdatePlayerTeamMessage.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(playerList.get(1).getPlayerId())
                    .targetPlayer(playerList.get(0).getPlayerId())
                    .targetTeam(mockGameSession.getTeams().get(0).getTeamId())
                    .build();

            SockbowlOutMessage result = processor.changeTeamForTargetPlayer(message);

            assertTrue(result instanceof ProcessErrorMessage);
            assertEquals("Player does not have permission to update team", ((ProcessErrorMessage) result).getError());
        }

        @Test
        @DisplayName("Non-existing target player causes error")
        void changeTeamForTargetPlayer_TargetPlayerDoesNotExist_ReturnsProcessErrorMessage() {

            UpdatePlayerTeamMessage message = UpdatePlayerTeamMessage.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(playerList.get(0).getPlayerId())
                    .targetPlayer("nonexistentPlayerId")
                    .targetTeam(mockGameSession.getTeams().get(0).getTeamId())
                    .build();

            SockbowlOutMessage result = processor.changeTeamForTargetPlayer(message);

            assertTrue(result instanceof ProcessErrorMessage);
            assertEquals("Target team or player does not exist", ((ProcessErrorMessage) result).getError());
        }

        @Test
        @DisplayName("Game owner can change team successfully")
        void changeTeamForTargetPlayer_PlayerIsGameOwner_ChangesTeamSuccessfully() {
            UpdatePlayerTeamMessage message = UpdatePlayerTeamMessage.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(playerList.get(0).getPlayerId())
                    .targetPlayer(playerList.get(1).getPlayerId())
                    .targetTeam(mockGameSession.getTeams().get(0).getTeamId())
                    .build();

            SockbowlOutMessage result = processor.changeTeamForTargetPlayer(message);

            assertTrue(result instanceof PlayerRosterUpdate);
        }

    }

    private List<Player> createPlayers(int numberOfPlayers) {
        return IntStream.rangeClosed(1, numberOfPlayers)
                .mapToObj(i -> {
                    Player player = Player.builder()
                            .playerId("TEST-PLAYER-" + i + "-ID")
                            .playerSecret("TEST-PLAYER-" + i + "-SECRET")
                            .build();
                    if(i == 1){
                        player.setGameOwner(true);
                    }

                    return player;
                })
                .collect(Collectors.toList());
    }

    private List<Team> createTeams(int numberOfTeams) {
        return IntStream.rangeClosed(1, numberOfTeams)
                .mapToObj(i -> {
                    Team team = new Team();
                    team.setTeamId("TEST-TEAM-" + i);
                    return team;
                })
                .collect(Collectors.toList());
    }
}


