package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.client.PacketClient;
import com.soulsoftworks.sockbowlquestions.models.nodes.*;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsBonus;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsTossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.SetMatchPacket;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.SetProctor;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.UpdateGameSettings;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.UpdatePlayerTeam;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlMultiOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.config.MatchPacketUpdate;
import com.soulsoftworks.sockbowlgame.model.socket.out.config.PlayerRosterUpdate;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.ProcessError;
import com.soulsoftworks.sockbowlgame.model.state.*;
import com.soulsoftworks.sockbowlgame.service.authorization.GameAuthorizationPolicy;
import com.soulsoftworks.sockbowlgame.util.PacketBuilderHelper;
import org.junit.jupiter.api.*;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ConfigurationMessageProcessorTest {

    private ConfigurationMessageProcessor processor;

    @Mock
    private PacketClient packetClient;

    // Real policy with auth disabled: session ownership falls back to the
    // in-memory isGameOwner flag, exercising the policy-based authorization path.
    private final GameAuthorizationPolicy authorizationPolicy =
            new GameAuthorizationPolicy(false, null);

    private GameSession mockGameSession;
    private Player gameOwner;
    private Player otherPlayer;

    private AutoCloseable closeable;

    @BeforeEach
    void setup() {
        closeable = MockitoAnnotations.openMocks(this);

        processor = new ConfigurationMessageProcessor(packetClient, authorizationPolicy,
                new com.soulsoftworks.sockbowlgame.support.InMemoryEphemeralPacketBindings());

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

        // Stubbing getProctor to return the game owner (who is also the proctor)
        when(mockGameSession.getProctor()).thenReturn(gameOwner);

        // Stubbing getPlayerModeById to return the correct player modes
        when(mockGameSession.getPlayerModeById(gameOwner.getPlayerId())).thenReturn(PlayerMode.PROCTOR);
        when(mockGameSession.getPlayerModeById(otherPlayer.getPlayerId())).thenReturn(PlayerMode.BUZZER);

        // Stubbing getGameSettings to return a default GameSettings with TimerSettings
        GameSettings gameSettings = GameSettings.builder()
                .timerSettings(new TimerSettings())
                .build();
        when(mockGameSession.getGameSettings()).thenReturn(gameSettings);

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
        bonus.setBonusParts(new ArrayList<>(List.of(new HasBonusPart(null, 1, bonusPart))));

        ContainsBonus containsBonus = PacketBuilderHelper.createBonus(1L, 1, bonus);

        // Use PacketBuilderHelper to create Packet with tossups and bonuses
        Packet mockPacket = PacketBuilderHelper.createPacket("1", "Default Packet", difficulty,
                new ArrayList<>(List.of(containsTossup)), new ArrayList<>(List.of(containsBonus)));

        // Mock packetClient to return the constructed packet wrapped in a Mono
        when(packetClient.getPacketById("1")).thenReturn(Mono.just(mockPacket));
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

        /**
         * The loader's full MatchPacketUpdate out of a SetMatchPacket reply. Since the
         * M2 fixes (R3-G-01) the reply is per recipient: every other copy must carry no
         * packet id but the same name and (M3, PB-13) the same tossup and playable-bonus
         * counts.
         */
        private MatchPacketUpdate loaderUpdate(SockbowlOutMessage result, String loaderId) {
            List<SockbowlOutMessage> frames = result instanceof SockbowlMultiOutMessage multi
                    ? multi.getSockbowlOutMessages() : List.of(result);
            frames.forEach(frame -> assertInstanceOf(MatchPacketUpdate.class, frame));
            MatchPacketUpdate full = frames.stream().map(MatchPacketUpdate.class::cast)
                    .filter(u -> u.getRecipients().contains(loaderId)).findFirst().orElseThrow();
            assertEquals(List.of(loaderId), full.getRecipients());
            assertNotNull(full.getPacketId());
            frames.stream().map(MatchPacketUpdate.class::cast).filter(u -> u != full).forEach(u -> {
                assertNull(u.getPacketId());
                assertFalse(u.getRecipients().contains(loaderId));
                assertEquals(full.getPacketName(), u.getPacketName());
                assertEquals(full.getTossupCount(), u.getTossupCount());
                assertEquals(full.getBonusCount(), u.getBonusCount());
            });
            return full;
        }

        @Test
        @DisplayName("Proctor sets the match packet successfully")
        void setPacketForMatch_ProctorSetsPacket_SuccessfullySetsPacket() {
            SetMatchPacket message = SetMatchPacket.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(gameOwner.getPlayerId())
                    .packetId("1")
                    .build();

            SockbowlOutMessage result = processor.setPacketForMatch(message);

            // The proctor gets the id; everyone else the name without it (R3-G-01).
            java.util.List<SockbowlOutMessage> frames = result instanceof SockbowlMultiOutMessage multi
                    ? multi.getSockbowlOutMessages() : java.util.List.of(result);
            MatchPacketUpdate update = frames.stream().map(MatchPacketUpdate.class::cast)
                    .filter(u -> u.getRecipients().contains(gameOwner.getPlayerId())).findFirst().orElseThrow();
            assertEquals(java.util.List.of(gameOwner.getPlayerId()), update.getRecipients());
            assertEquals("1", update.getPacketId());
            assertEquals("Default Packet", update.getPacketName());
            frames.stream().map(MatchPacketUpdate.class::cast).filter(u -> u != update).forEach(u -> {
                assertNull(u.getPacketId());
                assertEquals("Default Packet", u.getPacketName());
                assertFalse(u.getRecipients().contains(gameOwner.getPlayerId()));
            });
        }


        @Test
        @DisplayName("Non-proctor player tries to set the match packet causes error")
        void setPacketForMatch_NonProctorPlayerTriesToSetPacket_ReturnsProcessErrorMessage() {
            // Attempt to set the match packet by the non-proctor player (otherPlayer)
            SetMatchPacket message = SetMatchPacket.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(otherPlayer.getPlayerId())
                    .packetId("1000")
                    .build();

            SockbowlOutMessage result = processor.setPacketForMatch(message);

            // Assert that the result is an instance of ProcessError
            assertInstanceOf(ProcessError.class, result);
        }

        // ---- PB-13/D7: ordering and the empty-packet guard ----

        private Tossup tossup(String id, String question) {
            Tossup tossup = new Tossup();
            tossup.setId(id);
            tossup.setQuestion(question);
            tossup.setAnswer("answer-" + id);
            return tossup;
        }

        private Bonus threePartBonus(String id, String preamble) {
            Bonus bonus = PacketBuilderHelper.createBonus(id, preamble, null);
            bonus.setBonusParts(new ArrayList<>(List.of(
                    new HasBonusPart(null, 2, part("c")),
                    new HasBonusPart(null, 0, part("a")),
                    new HasBonusPart(null, 1, part("b")))));
            return bonus;
        }

        private BonusPart part(String suffix) {
            BonusPart part = new BonusPart();
            part.setId("part-" + suffix);
            part.setQuestion("q-" + suffix);
            part.setAnswer("a-" + suffix);
            return part;
        }

        @Test
        @DisplayName("Tossups, bonuses and bonus parts come back sorted by order when the packet arrives shuffled")
        void setPacketForMatch_ShuffledPacket_SortsTossupsBonusesAndParts() {
            // Wire order is shuffled: tossup 2 before tossup 1, bonus 2 before bonus 1.
            ContainsTossup t2 = PacketBuilderHelper.createTossup(2L, 1, tossup("t2", "Second tossup"));
            ContainsTossup t1 = PacketBuilderHelper.createTossup(1L, 0, tossup("t1", "First tossup"));
            ContainsBonus b2 = PacketBuilderHelper.createBonus(2L, 1, threePartBonus("bonus-2", "Second bonus"));
            ContainsBonus b1 = PacketBuilderHelper.createBonus(1L, 0, threePartBonus("bonus-1", "First bonus"));

            Packet shuffled = PacketBuilderHelper.createPacket("shuffled", "Shuffled Packet", null,
                    new ArrayList<>(List.of(t2, t1)), new ArrayList<>(List.of(b2, b1)));
            when(packetClient.getPacketById("shuffled")).thenReturn(Mono.just(shuffled));

            SetMatchPacket message = SetMatchPacket.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(gameOwner.getPlayerId())
                    .packetId("shuffled")
                    .build();

            SockbowlOutMessage result = processor.setPacketForMatch(message);

            MatchPacketUpdate update = loaderUpdate(result, gameOwner.getPlayerId());
            assertEquals(2, update.getTossupCount());
            assertEquals(2, update.getBonusCount());

            Packet stored = mockGameSession.getCurrentMatch().getPacket();
            assertEquals("t1", stored.getTossups().get(0).getTossup().getId());
            assertEquals("t2", stored.getTossups().get(1).getTossup().getId());
            assertEquals("bonus-1", stored.getBonuses().get(0).getBonus().getId());
            assertEquals("bonus-2", stored.getBonuses().get(1).getBonus().getId());

            List<HasBonusPart> parts = stored.getBonuses().get(0).getBonus().getBonusParts();
            assertEquals("part-a", parts.get(0).getBonusPart().getId());
            assertEquals("part-b", parts.get(1).getBonusPart().getId());
            assertEquals("part-c", parts.get(2).getBonusPart().getId());
        }

        @Test
        @DisplayName("Null tossups on the packet is rejected with PACKET_EMPTY")
        void setPacketForMatch_NullTossups_ReturnsPacketEmpty() {
            Packet empty = PacketBuilderHelper.createPacket("empty", "Empty Packet", null, null, null);
            when(packetClient.getPacketById("empty")).thenReturn(Mono.just(empty));

            SetMatchPacket message = SetMatchPacket.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(gameOwner.getPlayerId())
                    .packetId("empty")
                    .build();

            SockbowlOutMessage result = processor.setPacketForMatch(message);

            assertInstanceOf(ProcessError.class, result);
            assertEquals(ConfigurationMessageProcessor.PACKET_EMPTY, ((ProcessError) result).getCode());
        }

        @Test
        @DisplayName("Zero tossups on the packet is rejected with PACKET_EMPTY")
        void setPacketForMatch_ZeroTossups_ReturnsPacketEmpty() {
            Packet empty = PacketBuilderHelper.createPacket("empty2", "Empty Packet", null,
                    new ArrayList<>(), new ArrayList<>());
            when(packetClient.getPacketById("empty2")).thenReturn(Mono.just(empty));

            SetMatchPacket message = SetMatchPacket.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(gameOwner.getPlayerId())
                    .packetId("empty2")
                    .build();

            SockbowlOutMessage result = processor.setPacketForMatch(message);

            assertInstanceOf(ProcessError.class, result);
            assertEquals(ConfigurationMessageProcessor.PACKET_EMPTY, ((ProcessError) result).getCode());
        }

        @Test
        @DisplayName("A bonus with 0 parts is dropped and bonusCount reflects it")
        void setPacketForMatch_BonusWithNoParts_IsDroppedFromBonusCount() {
            ContainsTossup t1 = PacketBuilderHelper.createTossup(1L, 0, tossup("t1", "First tossup"));
            Bonus emptyBonus = PacketBuilderHelper.createBonus("empty-bonus", "No parts", null);
            emptyBonus.setBonusParts(null);
            ContainsBonus b1 = PacketBuilderHelper.createBonus(1L, 0, emptyBonus);
            ContainsBonus b2 = PacketBuilderHelper.createBonus(2L, 1, threePartBonus("bonus-2", "Second bonus"));

            Packet packet = PacketBuilderHelper.createPacket("mixed", "Mixed Packet", null,
                    new ArrayList<>(List.of(t1)), new ArrayList<>(List.of(b1, b2)));
            when(packetClient.getPacketById("mixed")).thenReturn(Mono.just(packet));

            SetMatchPacket message = SetMatchPacket.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(gameOwner.getPlayerId())
                    .packetId("mixed")
                    .build();

            SockbowlOutMessage result = processor.setPacketForMatch(message);

            MatchPacketUpdate update = loaderUpdate(result, gameOwner.getPlayerId());
            assertEquals(1, update.getBonusCount());

            Packet stored = mockGameSession.getCurrentMatch().getPacket();
            assertEquals(1, stored.getBonuses().size());
            assertEquals("bonus-2", stored.getBonuses().get(0).getBonus().getId());
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
            // Override the mock to return null for getProctor() in this test only
            when(mockGameSession.getProctor()).thenReturn(null);

            SetProctor message = SetProctor.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(mockGameSession.getPlayerList().get(1).getPlayerId()) // non-owner player
                    .targetPlayer(mockGameSession.getPlayerList().get(1).getPlayerId())
                    .build();

            SockbowlOutMessage result = processor.setPlayerAsProctor(message);

            assertInstanceOf(PlayerRosterUpdate.class, result);
        }
    }

    @Nested
    @DisplayName("SettingsTests")
    class SettingsTests {

        @Test
        @DisplayName("Proctor updates settings in CONFIG successfully")
        void updateGameSettings_ProctorInConfig_ReturnsGameSessionUpdate() {
            // Default setup: getCurrentMatch() is a fresh Match (CONFIG), gameOwner is proctor.
            UpdateGameSettings message = UpdateGameSettings.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(gameOwner.getPlayerId())
                    .gameSettings(GameSettings.builder().timerSettings(new TimerSettings()).build())
                    .build();

            SockbowlOutMessage result = processor.updateGameSettings(message);

            assertFalse(result instanceof ProcessError, "should be accepted in CONFIG state");
        }

        @Test
        @DisplayName("Settings update outside CONFIG is rejected (state guard)")
        void updateGameSettings_NotConfigState_ReturnsProcessError() {
            Match inGame = mock(Match.class);
            when(inGame.getMatchState()).thenReturn(MatchState.IN_GAME);
            when(mockGameSession.getCurrentMatch()).thenReturn(inGame);

            UpdateGameSettings message = UpdateGameSettings.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(gameOwner.getPlayerId()) // even the owner can't swap settings mid-match
                    .gameSettings(GameSettings.builder().timerSettings(new TimerSettings()).build())
                    .build();

            SockbowlOutMessage result = processor.updateGameSettings(message);

            assertInstanceOf(ProcessError.class, result);
        }
    }

}


