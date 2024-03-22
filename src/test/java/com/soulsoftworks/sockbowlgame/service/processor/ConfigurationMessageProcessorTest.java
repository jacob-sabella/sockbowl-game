package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.client.PacketClient;
import com.soulsoftworks.sockbowlgame.model.packet.nodes.*;
import com.soulsoftworks.sockbowlgame.model.packet.relationships.ContainsBonus;
import com.soulsoftworks.sockbowlgame.model.packet.relationships.ContainsTossup;
import com.soulsoftworks.sockbowlgame.model.packet.relationships.HasBonusPart;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.SetMatchPacket;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.SetProctor;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.UpdatePlayerTeam;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.config.MatchPacketUpdate;
import com.soulsoftworks.sockbowlgame.model.socket.out.config.PlayerRosterUpdate;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.ProcessError;
import com.soulsoftworks.sockbowlgame.model.state.*;
import com.soulsoftworks.sockbowlgame.util.PacketBuilderHelper;
import org.junit.jupiter.api.*;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ConfigurationMessageProcessorTest {

    @InjectMocks
    private ConfigurationMessageProcessor processor;

    @Mock
    private PacketClient packetClient;

    private GameSession mockGameSession;
    private Player gameOwner, otherPlayer;

    private AutoCloseable closeable;

    @BeforeEach
    void setup() {
        closeable = MockitoAnnotations.openMocks(this);

        gameOwner = Player.builder()
                .playerId("gameOwner")
                .name("Owner")
                .isGameOwner(true)
                .playerMode(PlayerMode.PROCTOR)
                .playerStatus(PlayerStatus.CONNECTED)
                .build();

        otherPlayer = Player.builder()
                .playerId("otherPlayer")
                .name("Player")
                .isGameOwner(false)
                .playerMode(PlayerMode.BUZZER)
                .playerStatus(PlayerStatus.CONNECTED)
                .build();

        // Create a team and add players
        Team mockTeam = new Team();
        mockTeam.setTeamName("Team 1");
        mockTeam.addPlayerToTeam(gameOwner); // Adding the game owner to the team
        mockTeam.addPlayerToTeam(otherPlayer); // Adding the other player to the team

        mockGameSession = mock(GameSession.class); // Mocking the GameSession

        // Mocking GameSession methods
        when(mockGameSession.getPlayerById(gameOwner.getPlayerId())).thenReturn(gameOwner);
        when(mockGameSession.getPlayerById(otherPlayer.getPlayerId())).thenReturn(otherPlayer);
        when(mockGameSession.findTeamWithId(anyString())).thenReturn(mockTeam); // Adjust as needed
        when(mockGameSession.getTeamByPlayerId(gameOwner.getPlayerId())).thenReturn(mockTeam);
        when(mockGameSession.getTeamByPlayerId(otherPlayer.getPlayerId())).thenReturn(mockTeam);
        when(mockGameSession.getTeamList()).thenReturn(List.of(mockTeam));
        when(mockGameSession.getCurrentMatch()).thenReturn(new Match());

        // Stubbing getPlayerList() to return a list containing gameOwner and otherPlayer
        when(mockGameSession.getPlayerList()).thenReturn(new ArrayList<>(List.of(gameOwner, otherPlayer)));

        // Stubbing isPlayerGameOwner to return true for the game owner and false for other players
        when(mockGameSession.isPlayerGameOwner(gameOwner.getPlayerId())).thenReturn(true);
        when(mockGameSession.isPlayerGameOwner(otherPlayer.getPlayerId())).thenReturn(false);

        Difficulty difficulty = PacketBuilderHelper.createDifficulty("1", "Easy");
        Category category = PacketBuilderHelper.createCategory("1", "Science");
        Subcategory subcategory = PacketBuilderHelper.createSubcategory("1", "Physics", category);

        // Create Tossup and wrap it in ContainsTossup
        Tossup tossup = new Tossup();
        tossup.setId("1");
        tossup.setQuestion("What is the speed of light?");
        tossup.setAnswer("299,792 km/s");
        tossup.setSubcategory(subcategory);
        ContainsTossup containsTossup = PacketBuilderHelper.createTossup(1L, 1, tossup);

        // Create Bonus, BonusPart, and wrap Bonus in ContainsBonus
        BonusPart bonusPart = new BonusPart(); // Assuming you set properties like id, question, and answer
        bonusPart.setId("1");
        bonusPart.setQuestion("Who discovered the law of gravitation?");
        bonusPart.setAnswer("Isaac Newton");

        // Assuming Bonus needs to be updated to include a list of BonusParts
        Bonus bonus = PacketBuilderHelper.createBonus("1", "About Newton's laws", subcategory);
        bonus.setBonusParts(new ArrayList<>(List.of(new HasBonusPart(1, bonusPart))));

        ContainsBonus containsBonus = PacketBuilderHelper.createBonus(1L, 1, bonus);

        // Use PacketBuilderHelper to create Packet with tossups and bonuses
        Packet mockPacket = PacketBuilderHelper.createPacket(1L, "Default Packet", difficulty,
                new ArrayList<>(List.of(containsTossup)), new ArrayList<>(List.of(containsBonus)));

        // Mock packetClient to return the constructed packet
        when(packetClient.getPacketById(1L)).thenReturn(mockPacket);
    }

    @AfterEach
    void tearDown() throws Exception {
        closeable.close(); // Ensure resources are closed and Mockito annotations are cleaned up
    }

    @Nested
    @DisplayName("TeamTests")
    class TeamTests {

        @Test
        @DisplayName("Player without permission to update a team causes error")
        void changeTeamForTargetPlayer_PlayerDoesNotHavePermissionToUpdateTeam_ReturnsProcessErrorMessage() {
            UpdatePlayerTeam message = UpdatePlayerTeam.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(otherPlayer.getPlayerId()) // Using otherPlayer who is not the game owner
                    .targetPlayer(gameOwner.getPlayerId()) // Targeting the game owner for the team change
                    .targetTeam(mockGameSession.getTeamList().get(0).getTeamId()) // Assuming the first team in the list
                    .build();

            SockbowlOutMessage result = processor.changeTeamForTargetPlayer(message);

            assertInstanceOf(ProcessError.class, result);
            ProcessError errorResult = (ProcessError) result;
            assertEquals("UpdatePlayerTeam: Permission Denied", errorResult.getError()); // Assuming "Access Denied" is the error message
        }


        @Test
        @DisplayName("Non-existing target player causes error")
        void changeTeamForTargetPlayer_TargetPlayerDoesNotExist_ReturnsProcessErrorMessage() {
            UpdatePlayerTeam message = UpdatePlayerTeam.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(gameOwner.getPlayerId()) // Using gameOwner who has permission
                    .targetPlayer("nonexistentPlayerId") // Using a non-existent player ID
                    .targetTeam(mockGameSession.getTeamList().get(0).getTeamId()) // Assuming the first team in the list
                    .build();

            SockbowlOutMessage result = processor.changeTeamForTargetPlayer(message);

            assertInstanceOf(ProcessError.class, result);
            ProcessError errorResult = (ProcessError) result;
            assertEquals("Target team or player does not exist", errorResult.getError()); // Adjust error message as needed
        }


        @Test
        @DisplayName("Game owner can change team successfully")
        void changeTeamForTargetPlayer_PlayerIsGameOwner_ChangesTeamSuccessfully() {
            // Mock the Match and configure it to CONFIG state
            Match mockMatch = mock(Match.class);
            when(mockMatch.getMatchState()).thenReturn(MatchState.CONFIG);
            when(mockGameSession.getCurrentMatch()).thenReturn(mockMatch);

            // Mock a new Team and configure it
            Team newTeam = mock(Team.class);
            String newTeamId = UUID.randomUUID().toString();
            when(newTeam.getTeamId()).thenReturn(newTeamId);
            when(newTeam.isPlayerOnTeam(otherPlayer.getPlayerId())).thenReturn(false); // Ensure the player is not already in the team

            // Configure the mockGameSession to return the new mock team within its team list
            when(mockGameSession.getTeamList()).thenReturn(List.of(newTeam));
            // Stub the findTeamWithId to return the new mock team
            when(mockGameSession.findTeamWithId(newTeamId)).thenReturn(newTeam);

            // Ensure the target player is found within the game session
            when(mockGameSession.getPlayerById(otherPlayer.getPlayerId())).thenReturn(otherPlayer);

            // Build the message
            UpdatePlayerTeam message = UpdatePlayerTeam.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(gameOwner.getPlayerId()) // Game owner initiates the request
                    .targetPlayer(otherPlayer.getPlayerId()) // Other player is the target
                    .targetTeam(newTeamId) // New team is the target
                    .build();

            // Execute the method under test
            SockbowlOutMessage result = processor.changeTeamForTargetPlayer(message);

            // Validate the result
            assertInstanceOf(PlayerRosterUpdate.class, result);
        }




    }

    @Nested
    @DisplayName("PacketTests")
    class PacketTests {

        @Test
        @DisplayName("Game owner sets the match packet successfully")
        void setPacketForMatch_OwnerPlayerSetsPacket_SuccessfullySetsPacket() {
            SetMatchPacket message = SetMatchPacket.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(gameOwner.getPlayerId())
                    .packetId(1L)
                    .build();

            SockbowlOutMessage result = processor.setPacketForMatch(message);

            assertInstanceOf(MatchPacketUpdate.class, result);
            MatchPacketUpdate update = (MatchPacketUpdate) result;
            assertEquals(1L, update.getPacketId());
            assertEquals("Default Packet", update.getPacketName());
        }


        @Test
        @DisplayName("Non-owner player tries to set the match packet causes error")
        void setPacketForMatch_NonOwnerPlayerTriesToSetPacket_ReturnsProcessErrorMessage() {
            // Ensure the game session has a current match set up
            Match currentMatch = new Match();
            mockGameSession.setCurrentMatch(currentMatch);

            // Create and add players to the game session, marking the first as the game owner
            Player gameOwner = Player.builder()
                    .playerId("ownerId")
                    .isGameOwner(true)
                    .name("Owner")
                    .build();

            Player nonOwner = Player.builder()
                    .playerId("nonOwnerId")
                    .isGameOwner(false)
                    .name("Non Owner")
                    .build();

            mockGameSession.getPlayerList().addAll(List.of(gameOwner, nonOwner));

            // Attempt to set the match packet by the non-owner player
            SetMatchPacket message = SetMatchPacket.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(nonOwner.getPlayerId())
                    .packetId(1000)
                    .build();

            SockbowlOutMessage result = processor.setPacketForMatch(message);

            // Assert that the result is an instance of ProcessError
            assertInstanceOf(ProcessError.class, result);
        }

    }

    @Nested
    class ProctorTests {

        @Test
        @DisplayName("Non-owner player tries to set another player as proctor causes error")
        void setPlayerAsProctor_NonOwnerPlayerTriesToSetProctor_ReturnsProcessErrorMessage() {
            SetProctor message = SetProctor.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(mockGameSession.getPlayerList().get(1).getPlayerId()) // non-owner player
                    .targetPlayer(mockGameSession.getPlayerList().get(0).getPlayerId())
                    .build();

            SockbowlOutMessage result = processor.setPlayerAsProctor(message);

            assertInstanceOf(ProcessError.class, result);
            assertEquals("SetProctor: Permission Denied", ((ProcessError) result).getError());
        }

        @Test
        @DisplayName("Owner player successfully sets another player as proctor")
        void setPlayerAsProctor_OwnerPlayerSetsProctor_SuccessfullySetsProctor() {
            SetProctor message = SetProctor.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(mockGameSession.getPlayerList().get(0).getPlayerId()) // owner player
                    .targetPlayer(mockGameSession.getPlayerList().get(1).getPlayerId())
                    .build();

            SockbowlOutMessage result = processor.setPlayerAsProctor(message);

            assertInstanceOf(PlayerRosterUpdate.class, result);
        }

        @Test
        @DisplayName("Owner player tries to set a non-existing player as proctor causes error")
        void setPlayerAsProctor_NonExistingPlayer_ReturnsProcessErrorMessage() {
            SetProctor message = SetProctor.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(mockGameSession.getPlayerList().get(0).getPlayerId()) // owner player
                    .targetPlayer("nonexistentPlayerId")
                    .build();

            SockbowlOutMessage result = processor.setPlayerAsProctor(message);

            assertInstanceOf(ProcessError.class, result);
            assertEquals("Player id nonexistentPlayerId does not exist", ((ProcessError) result).getError());
        }

        @Test
        @DisplayName("Player tries to set themselves as proctor successfully")
        void setPlayerAsProctor_PlayerSetsThemselvesAsProctor_SuccessfullySetsProctor() {
            SetProctor message = SetProctor.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(mockGameSession.getPlayerList().get(1).getPlayerId()) // non-owner player
                    .targetPlayer(mockGameSession.getPlayerList().get(1).getPlayerId())
                    .build();

            SockbowlOutMessage result = processor.setPlayerAsProctor(message);

            assertInstanceOf(PlayerRosterUpdate.class, result);
        }
    }

}


