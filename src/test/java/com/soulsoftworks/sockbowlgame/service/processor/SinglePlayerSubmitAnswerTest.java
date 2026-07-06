package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.model.socket.in.game.SubmitAnswer;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlMultiOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.ProcessError;
import com.soulsoftworks.sockbowlgame.model.socket.out.game.AnswerUpdate;
import com.soulsoftworks.sockbowlgame.model.state.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.soulsoftworks.sockbowlgame.service.processor.MatchContextUtils.createTeams;
import static org.junit.jupiter.api.Assertions.*;

/** In-memory tests for the single-player auto-judge submit path. */
class SinglePlayerSubmitAnswerTest {

    private GameMessageProcessor processor;
    private GameSession session;
    private Player player;

    private static final String ANSWER_LINE = "<b><u>Napoleon</u></b> Bonaparte";

    @BeforeEach
    void setup() {
        processor = new GameMessageProcessor();

        player = Player.builder()
                .playerId("soloPlayer")
                .name("solo")
                .playerMode(PlayerMode.BUZZER)
                .isGameOwner(true)
                .build();

        List<Team> teams = createTeams(1);
        teams.get(0).addPlayerToTeam(player);

        GameSettings settings = new GameSettings();
        settings.setGameMode(GameMode.SINGLE_PLAYER);

        Round round = new Round();
        round.setRoundState(RoundState.AWAITING_BUZZ);
        round.setAnswer(ANSWER_LINE);

        Match match = new Match();
        match.setMatchState(MatchState.IN_GAME);
        match.setCurrentRound(round);

        session = GameSession.builder()
                .id("SP")
                .joinCode("SOLO")
                .playerList(List.of(player))
                .teamList(teams)
                .currentMatch(match)
                .gameSettings(settings)
                .build();
    }

    private SubmitAnswer submit(String text) {
        return SubmitAnswer.builder()
                .gameSession(session)
                .originatingPlayerId(player.getPlayerId())
                .answerText(text)
                .build();
    }

    private AnswerUpdate firstAnswerUpdate(SockbowlOutMessage result) {
        assertInstanceOf(SockbowlMultiOutMessage.class, result);
        SockbowlOutMessage first = ((SockbowlMultiOutMessage) result).getSockbowlOutMessages().get(0);
        assertInstanceOf(AnswerUpdate.class, first);
        return (AnswerUpdate) first;
    }

    @Test
    @DisplayName("Correct answer scores the tossup and completes the round")
    void correctAnswer() {
        SockbowlOutMessage result = processor.playerSubmitAnswer(submit("Napoleon"));

        AnswerUpdate update = firstAnswerUpdate(result);
        assertTrue(update.isCorrect());
        assertEquals(RoundState.COMPLETED, session.getCurrentRound().getRoundState());
    }

    @Test
    @DisplayName("Fuzzy/typo answer still scores correct")
    void typoAnswer() {
        AnswerUpdate update = firstAnswerUpdate(processor.playerSubmitAnswer(submit("napolean")));
        assertTrue(update.isCorrect());
    }

    @Test
    @DisplayName("Wrong answer marks incorrect, completes the round, and reveals the answer")
    void wrongAnswer() {
        SockbowlOutMessage result = processor.playerSubmitAnswer(submit("Hitler"));

        AnswerUpdate update = firstAnswerUpdate(result);
        assertFalse(update.isCorrect());
        assertEquals(RoundState.COMPLETED, session.getCurrentRound().getRoundState());
        // Round completed → the full answer line is revealed to the player.
        assertEquals(ANSWER_LINE, session.getCurrentRound().getAnswer());
    }

    @Test
    @DisplayName("Submit is rejected outside single-player mode")
    void rejectedInProctoredMode() {
        session.getGameSettings().setGameMode(GameMode.QUIZ_BOWL_CLASSIC);
        SockbowlOutMessage result = processor.playerSubmitAnswer(submit("Napoleon"));
        assertInstanceOf(ProcessError.class, result);
    }

    @Test
    @DisplayName("Submit is rejected when the round is not awaiting a buzz")
    void rejectedInWrongState() {
        session.getCurrentRound().setRoundState(RoundState.COMPLETED);
        SockbowlOutMessage result = processor.playerSubmitAnswer(submit("Napoleon"));
        assertInstanceOf(ProcessError.class, result);
    }
}
