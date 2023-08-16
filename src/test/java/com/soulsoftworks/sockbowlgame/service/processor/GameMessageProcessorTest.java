package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.model.socket.in.game.AnswerCorrect;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.AnswerIncorrect;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.PlayerIncomingBuzz;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.ProcessError;
import com.soulsoftworks.sockbowlgame.model.socket.out.game.CorrectAnswer;
import com.soulsoftworks.sockbowlgame.model.socket.out.game.IncorrectAnswer;
import com.soulsoftworks.sockbowlgame.model.socket.out.game.PlayerBuzzed;
import com.soulsoftworks.sockbowlgame.model.state.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static com.soulsoftworks.sockbowlgame.service.processor.MatchContextUtils.createPlayers;
import static com.soulsoftworks.sockbowlgame.service.processor.MatchContextUtils.createTeams;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GameMessageProcessorTest {

    private GameMessageProcessor processor;

    private GameSession mockGameSession;

    private final List<Player> playerList = createPlayers(2);
    private final List<Team> teams = createTeams(2);

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        processor = new GameMessageProcessor();

        Match mockMatch = new Match();
        mockMatch.setMatchState(MatchState.IN_GAME);
        Round mockRound = new Round();
        mockRound.setRoundState(RoundState.PROCTOR_READING);
        mockMatch.setCurrentRound(mockRound);

        mockGameSession = GameSession.builder()
                .id("TEST")
                .joinCode("TEST")
                .playerList(playerList)
                .currentMatch(mockMatch)
                .gameSettings(new GameSettings())
                .teams(teams)
                .build();

        mockGameSession.getTeams().get(0).addPlayerToTeam(playerList.get(0));
        mockGameSession.getTeams().get(1).addPlayerToTeam(playerList.get(1));
    }

    @Nested
    @DisplayName("Buzz Tests")
    class BuzzTests {

        @Test
        @DisplayName("Player in BUZZER mode successfully buzzes during valid round state")
        void processPlayerBuzz_BuzzerModePlayerBuzzesDuringValidRoundState_ReturnsPlayerBuzzedMessage() {
            playerList.get(0).setPlayerMode(PlayerMode.BUZZER);
            mockGameSession.getCurrentMatch().getCurrentRound().setRoundState(RoundState.PROCTOR_READING);

            PlayerIncomingBuzz message = PlayerIncomingBuzz.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(playerList.get(0).getPlayerId())
                    .build();

            SockbowlOutMessage result = processor.playerBuzz(message);

            assertTrue(result instanceof PlayerBuzzed);
            assertEquals(playerList.get(0).getPlayerId(), ((PlayerBuzzed) result).getPlayerId());
            assertEquals(mockGameSession.getTeamByPlayerId(playerList.get(0).getPlayerId()).getTeamId(), ((PlayerBuzzed) result).getTeamId());
        }

        @Test
        @DisplayName("Player in BUZZER mode successfully buzzes while awaiting buzz")
        void processPlayerBuzz_BuzzerModePlayerBuzzesWhileAwaitingBuzz_ReturnsPlayerBuzzedMessage() {
            playerList.get(0).setPlayerMode(PlayerMode.BUZZER);
            mockGameSession.getCurrentMatch().getCurrentRound().setRoundState(RoundState.AWAITING_BUZZ);

            PlayerIncomingBuzz message = PlayerIncomingBuzz.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(playerList.get(0).getPlayerId())
                    .build();

            SockbowlOutMessage result = processor.playerBuzz(message);

            assertTrue(result instanceof PlayerBuzzed);
            assertEquals(playerList.get(0).getPlayerId(), ((PlayerBuzzed) result).getPlayerId());
            assertEquals(mockGameSession.getTeamByPlayerId(playerList.get(0).getPlayerId()).getTeamId(),
                    ((PlayerBuzzed) result).getTeamId());
        }

        @Test
        @DisplayName("Player not in BUZZER mode tries to buzz causes error")
        void processPlayerBuzz_NonBuzzerModePlayerTriesToBuzz_ReturnsProcessErrorMessage() {
            PlayerIncomingBuzz message = PlayerIncomingBuzz.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(playerList.get(1).getPlayerId())
                    .build();

            SockbowlOutMessage result = processor.playerBuzz(message);

            assertTrue(result instanceof ProcessError);
            assertEquals("Player mode is not buzzer", ((ProcessError) result).getError());
        }

        @Test
        @DisplayName("Buzzer mode player buzzes in unsupported round state causes error")
        void processPlayerBuzz_BuzzerModePlayerBuzzesInUnsupportedRoundState_ReturnsProcessErrorMessage() {
            playerList.get(0).setPlayerMode(PlayerMode.BUZZER);
            mockGameSession.getCurrentMatch().getCurrentRound().setRoundState(RoundState.AWAITING_ANSWER);

            PlayerIncomingBuzz message = PlayerIncomingBuzz.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(playerList.get(0).getPlayerId())
                    .build();

            SockbowlOutMessage result = processor.playerBuzz(message);

            assertTrue(result instanceof ProcessError);
            assertEquals("Buzz processed when round is in unsupported state", ((ProcessError) result).getError());
        }
    }

    @Nested
    @DisplayName("Player Answer Tests")
    class PlayerAnswerTests {

        @BeforeEach
        public void beforeEach(){
            mockGameSession.getCurrentRound().processBuzz(playerList.get(0).getPlayerId(),
                    mockGameSession.getTeamByPlayerId(playerList.get(0).getPlayerId()).getTeamId());
        }

        @Test
        @DisplayName("Proctor player processes correct answer during AWAITING_ANSWER round state")
        void processPlayerAnswer_ProctorProcessesCorrectAnswerInSupportedState_ReturnsCorrectAnswer() {
            playerList.get(0).setPlayerMode(PlayerMode.PROCTOR);
            mockGameSession.getCurrentMatch().getCurrentRound().setRoundState(RoundState.AWAITING_ANSWER);

            AnswerCorrect message = AnswerCorrect.builder()
                    .gameSession(mockGameSession)
                    .originatingPlayerId(playerList.get(0).getPlayerId())
                    .build();

            SockbowlOutMessage result = processor.playerAnswer(message);

            assertTrue(result instanceof CorrectAnswer);
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

            assertTrue(result instanceof IncorrectAnswer);
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
    }

}
