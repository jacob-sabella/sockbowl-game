package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.client.PacketClient;
import com.soulsoftworks.sockbowlgame.model.game.socket.in.config.SetMatchPacketMessage;
import com.soulsoftworks.sockbowlgame.model.game.socket.in.config.UpdatePlayerTeamMessage;
import com.soulsoftworks.sockbowlgame.model.game.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.game.socket.out.config.MatchPacketUpdate;
import com.soulsoftworks.sockbowlgame.model.game.socket.out.config.PlayerRosterUpdate;
import com.soulsoftworks.sockbowlgame.model.game.socket.out.error.ProcessErrorMessage;
import com.soulsoftworks.sockbowlgame.model.game.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.game.state.GameSettings;
import com.soulsoftworks.sockbowlgame.model.game.state.Player;
import com.soulsoftworks.sockbowlgame.model.game.state.Team;
import com.soulsoftworks.sockbowlgame.model.packet.Packet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConfigurationMessageProcessorTest {

    private ConfigurationMessageProcessor processor;

    private GameSession mockGameSession;
    @Mock
    private PacketClient packetClient;

    private final List<Player> playerList = createPlayers(2);

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        processor = new ConfigurationMessageProcessor(packetClient);

        mockGameSession = GameSession.builder()
                .id("TEST")
                .gameSettings(new GameSettings())
                .joinCode("TEST")
                .teams(createTeams(2))
                .playerList(playerList)
                .build();
    }


    @Nested
    class TeamTests {

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
            assertEquals(ProcessErrorMessage.accessDeniedMessage(message).getError(),
                    ((ProcessErrorMessage) result).getError());
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

    @Nested
    class PacketTests {
        @BeforeEach
        void setup() {
            // Mock the PacketClient to return a generic Packet
            Packet packet = new Packet();
            packet.setId(1000);
            packet.setName("packetName");
            Mockito.when(packetClient.getPacketById(Mockito.anyLong())).thenReturn(packet);
        }

        @Test
        @DisplayName("Non-owner player tries to set the match packet causes error")
        void setPacketForMatch_NonOwnerPlayerTriesToSetPacket_ReturnsProcessErrorMessage() {
            SetMatchPacketMessage message = SetMatchPacketMessage.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(playerList.get(1).getPlayerId())
                    .packetId(1000)
                    .build();

            SockbowlOutMessage result = processor.setPacketForMatch(message);

            assertTrue(result instanceof ProcessErrorMessage);
        }

        @Test
        @DisplayName("Game owner sets the match packet successfully")
        void setPacketForMatch_OwnerPlayerSetsPacket_SuccessfullySetsPacket() {
            SetMatchPacketMessage message = SetMatchPacketMessage.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(playerList.get(0).getPlayerId())
                    .packetId(1000)
                    .build();

            SockbowlOutMessage result = processor.setPacketForMatch(message);

            assertTrue(result instanceof MatchPacketUpdate);
            assertEquals(1000, ((MatchPacketUpdate) result).getPacketId());
            assertEquals("packetName", ((MatchPacketUpdate) result).getPacketName());
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


