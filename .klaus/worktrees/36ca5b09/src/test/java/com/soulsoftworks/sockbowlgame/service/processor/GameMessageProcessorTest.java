package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.model.socket.in.game.PlayerIncomingBuzz;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlMultiOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.ProcessError;
import com.soulsoftworks.sockbowlgame.model.socket.out.game.PlayerBuzzed;
import com.soulsoftworks.sockbowlgame.model.state.*;
import org.junit.jupiter.api.*;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static com.soulsoftworks.sockbowlgame.service.processor.MatchContextUtils.createTeams;
import static org.junit.jupiter.api.Assertions.*;

public class GameMessageProcessorTest {

    private GameMessageProcessor processor;

    private GameSession mockGameSession;
    private final List<Team> teams = createTeams(2);

    private AutoCloseable closeable;

    @BeforeEach
    void setup() {
        closeable = MockitoAnnotations.openMocks(this);
        processor = new GameMessageProcessor();

        // Initialize players with their roles
        Player proctor = Player.builder()
                .playerId("proctorId")
                .name("proctor")
                .playerMode(PlayerMode.PROCTOR)
                .isGameOwner(true) // Assuming the proctor is the game owner
                .build();

        Player buzzerPlayer = Player.builder()
                .playerId("buzzerPlayerId")
                .name("buzzer")
                .playerMode(PlayerMode.BUZZER)
                .isGameOwner(false)
                .build();

        // Add players to the game session
        List<Player> sessionPlayers = List.of(proctor, buzzerPlayer);

        // Mocking GameSession and its methods
        // Mock GameSession and set up its state and relationships
        mockGameSession = GameSession.builder()
                .id("TEST")
                .joinCode("ABCD")
                .playerList(sessionPlayers) // Use the populated playerList
                .teamList(teams) // Use the populated list of teams
                .currentMatch(new Match()) // Ensure Match is properly initialized
                .gameSettings(new GameSettings()) // Ensure GameSettings is properly initialized
                .build();
        //mockGameSession = mock(GameSession.class);
        //when(mockGameSession.getPlayerList()).thenReturn(sessionPlayers);
        //when(mockGameSession.getProctor()).thenReturn(proctor); // Ensure getProctor() returns the proctor

        // Setup match and round states
        Match mockMatch = new Match();
        mockMatch.setMatchState(MatchState.IN_GAME);
        Round mockRound = new Round();
        mockRound.setRoundState(RoundState.PROCTOR_READING);
        mockMatch.setCurrentRound(mockRound);
        //when(mockGameSession.getCurrentMatch()).thenReturn(mockMatch);

        mockGameSession.getTeamList().get(0).addPlayerToTeam(sessionPlayers.get(0));
        mockGameSession.getTeamList().get(1).addPlayerToTeam(sessionPlayers.get(1));
    }

    @AfterEach
    void tearDown() throws Exception {
        closeable.close(); // Ensure resources are closed and Mockito annotations are cleaned up
    }

    @Nested
    @DisplayName("Buzz Tests")
    class BuzzTests {

        @Test
        @DisplayName("Player in BUZZER mode successfully buzzes during valid round state")
        void processPlayerBuzz_BuzzerModePlayerBuzzesDuringValidRoundState_ReturnsPlayerBuzzedMessage() {
            mockGameSession.getCurrentMatch().getCurrentRound().setRoundState(RoundState.PROCTOR_READING);

            PlayerIncomingBuzz message = PlayerIncomingBuzz.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(mockGameSession.getPlayerList().get(1).getPlayerId())
                    .build();

            SockbowlOutMessage result = processor.playerBuzz(message);

            String mockPlayerId = mockGameSession.getPlayerList().get(1).getPlayerId();
            String outputPlayerId = ((PlayerBuzzed) ((SockbowlMultiOutMessage) result).getSockbowlOutMessages().get(1)).getPlayerId();
            String mockTeamIdByPlayer1 = mockGameSession.getTeamByPlayerId(mockGameSession.getPlayerList().get(1).getPlayerId()).getTeamId();
            String outputTeamIdByPlayer1 = ((PlayerBuzzed) ((SockbowlMultiOutMessage) result).getSockbowlOutMessages().get(1)).getTeamId();

            //ASSERT
            assertInstanceOf(SockbowlMultiOutMessage.class, result);
            assertEquals(mockPlayerId, outputPlayerId);
            assertEquals(mockTeamIdByPlayer1, outputTeamIdByPlayer1);
        }

        @Test
        @DisplayName("Player in BUZZER mode successfully buzzes while awaiting buzz")
        void processPlayerBuzz_BuzzerModePlayerBuzzesWhileAwaitingBuzz_ReturnsPlayerBuzzedMessage() {
            mockGameSession.getCurrentMatch().getCurrentRound().setRoundState(RoundState.AWAITING_BUZZ);

            PlayerIncomingBuzz message = PlayerIncomingBuzz.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(mockGameSession.getPlayerList().get(1).getPlayerId())
                    .build();

            SockbowlOutMessage result = processor.playerBuzz(message);

            String mockPlayerId = mockGameSession.getPlayerList().get(1).getPlayerId();
            String outputPlayerId = ((PlayerBuzzed) ((SockbowlMultiOutMessage) result).getSockbowlOutMessages().get(1)).getPlayerId();
            String mockTeamIdByPlayer1 = mockGameSession.getTeamByPlayerId(mockGameSession.getPlayerList().get(1).getPlayerId()).getTeamId();
            String outputTeamIdByPlayer1 = ((PlayerBuzzed) ((SockbowlMultiOutMessage) result).getSockbowlOutMessages().get(1)).getTeamId();

            //ASSERT
            assertInstanceOf(SockbowlMultiOutMessage.class, result);
            assertEquals(mockPlayerId, outputPlayerId);
            assertEquals(mockTeamIdByPlayer1, outputTeamIdByPlayer1);
        }

        @Test
        @DisplayName("Player not in BUZZER mode tries to buzz causes error")
        void processPlayerBuzz_NonBuzzerModePlayerTriesToBuzz_ReturnsProcessErrorMessage() {
            PlayerIncomingBuzz message = PlayerIncomingBuzz.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(mockGameSession.getPlayerList().get(0).getPlayerId())
                    .build();

            SockbowlOutMessage result = processor.playerBuzz(message);

            assertInstanceOf(ProcessError.class, result);
            assertEquals("Player mode is not buzzer", ((ProcessError) result).getError());
        }

        @Test
        @DisplayName("Buzzer mode player buzzes in unsupported round state causes error")
        void processPlayerBuzz_BuzzerModePlayerBuzzesInUnsupportedRoundState_ReturnsProcessErrorMessage() {
            mockGameSession.getPlayerList().get(0).setPlayerMode(PlayerMode.BUZZER);
            mockGameSession.getCurrentMatch().getCurrentRound().setRoundState(RoundState.AWAITING_ANSWER);

            PlayerIncomingBuzz message = PlayerIncomingBuzz.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(mockGameSession.getPlayerList().get(0).getPlayerId())
                    .build();

            SockbowlOutMessage result = processor.playerBuzz(message);

            assertInstanceOf(ProcessError.class, result);
            assertEquals("Buzz processed when round is in unsupported state", ((ProcessError) result).getError());
        }
    }

    /*@Nested
    @DisplayName("Player Answer Tests")
    class PlayerAnswerTests {

        @BeforeEach
        public void beforeEach() {
            mockGameSession.getCurrentRound().processBuzz(playerList.get(0).getPlayerId(),
                    mockGameSession.getTeamByPlayerId(playerList.get(0).getPlayerId()).getTeamId());
        }


        @Test
        @DisplayName("Proctor player processes correct answer during AWAITING_ANSWER round state")
        void processPlayerAnswer_ProctorProcessesCorrectAnswerInSupportedState_ReturnsCorrectAnswer() {
            playerList.get(0).setPlayerMode(PlayerMode.PROCTOR);
            mockGameSession.getCurrentMatch().getCurrentRound().setRoundState(RoundState.AWAITING_ANSWER);

            // Assuming AnswerOutcome represents a correct answer processed by the proctor
            SockbowlOutMessage message = new SockbowlOutMessage(); // Replace with actual message construction if needed
            SockbowlOutMessage result = processor.playerAnswer(message);

            assertInstanceOf(AnswerOutcome.class, result); // Assuming AnswerOutcome indicates a correct answer
        }


        @Test
        @DisplayName("Proctor player processes incorrect answer during AWAITING_ANSWER round state")
        void processPlayerAnswer_ProctorProcessesIncorrectAnswerInSupportedState_ReturnsIncorrectAnswer() {
            playerList.get(0).setPlayerMode(PlayerMode.PROCTOR);
            mockGameSession.getCurrentMatch().getCurrentRound().setRoundState(RoundState.AWAITING_ANSWER);

            AnswerIncorrect message = AnswerIncorrect.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(playerList.get(0).getPlayerId())
                    .build();

            SockbowlOutMessage result = processor.playerAnswer(message);

            assertTrue(result instanceof AnswerUpdate);
        }

        @Test
        @DisplayName("Non-proctor player tries to process answer")
        void processPlayerAnswer_NonProctorTriesToProcessAnswer_ReturnsProcessErrorMessage() {
            playerList.get(1).setPlayerMode(PlayerMode.BUZZER);
            mockGameSession.getCurrentMatch().getCurrentRound().setRoundState(RoundState.AWAITING_ANSWER);

            AnswerCorrect message = AnswerCorrect.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(playerList.get(1).getPlayerId())
                    .build();

            SockbowlOutMessage result = processor.playerAnswer(message);

            assertTrue(result instanceof ProcessError);
            assertEquals("Originating player is not the proctor", ((ProcessError) result).getError());
        }

        @Test
        @DisplayName("Proctor processes answer in unsupported round state causes error")
        void processPlayerAnswer_ProctorProcessesAnswerInUnsupportedState_ReturnsProcessErrorMessage() {
            playerList.get(0).setPlayerMode(PlayerMode.PROCTOR);
            mockGameSession.getCurrentMatch().getCurrentRound().setRoundState(RoundState.PROCTOR_READING);

            AnswerCorrect message = AnswerCorrect.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(playerList.get(0).getPlayerId())
                    .build();

            SockbowlOutMessage result = processor.playerAnswer(message);

            assertTrue(result instanceof ProcessError);
            assertEquals("Answer incorrect message processed when round is in unsupported state", ((ProcessError) result).getError());
        }
    }*/
}

