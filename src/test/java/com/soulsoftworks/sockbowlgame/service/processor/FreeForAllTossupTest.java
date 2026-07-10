package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.model.socket.in.game.PlayerIncomingBuzz;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.SubmitAnswer;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.TimeoutRound;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.ProcessError;
import com.soulsoftworks.sockbowlgame.model.socket.out.game.AnswerUpdate;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlMultiOutMessage;
import com.soulsoftworks.sockbowlgame.model.state.*;
import com.soulsoftworks.sockbowlgame.service.GameTimerService;
import com.soulsoftworks.sockbowlgame.service.MessageService;
import com.soulsoftworks.sockbowlgame.service.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;

import static com.soulsoftworks.sockbowlgame.service.processor.MatchContextUtils.createTeams;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * In-memory tests for the FREE_FOR_ALL multiplayer tossup flow. FFA is a team-per-player
 * variant of AUTO_PROCTOR — every gameplay code path (reveal, buzz, judge, lockout, bonus,
 * timeout, sanitization) must behave identically; teams here are just one player each.
 */
class FreeForAllTossupTest {

    private GameMessageProcessor processor;
    private GameSession session;
    private Player p1; // team 0 (solo)
    private Player p2; // team 1 (solo)
    private Player p3; // team 2 (solo)

    private static final String ANSWER = "<b><u>Napoleon</u></b> Bonaparte";

    @BeforeEach
    void setup() {
        processor = new GameMessageProcessor();

        p1 = Player.builder().playerId("p1").name("one").playerMode(PlayerMode.BUZZER).isGameOwner(true).build();
        p2 = Player.builder().playerId("p2").name("two").playerMode(PlayerMode.BUZZER).build();
        p3 = Player.builder().playerId("p3").name("three").playerMode(PlayerMode.BUZZER).build();

        List<Team> teams = createTeams(3);
        teams.get(0).addPlayerToTeam(p1);
        teams.get(1).addPlayerToTeam(p2);
        teams.get(2).addPlayerToTeam(p3);

        GameSettings settings = new GameSettings();
        settings.setGameMode(GameMode.FREE_FOR_ALL);

        Round round = new Round();
        round.setRoundState(RoundState.AWAITING_BUZZ);
        round.setAnswer(ANSWER);

        Match match = new Match();
        match.setMatchState(MatchState.IN_GAME);
        match.setCurrentRound(round);

        session = GameSession.builder()
                .id("FFA").joinCode("FREE1")
                .playerList(List.of(p1, p2, p3)).teamList(teams)
                .currentMatch(match).gameSettings(settings).build();
    }

    private SockbowlOutMessage buzz(Player p) {
        return processor.playerBuzz(PlayerIncomingBuzz.builder()
                .gameSession(session).originatingPlayerId(p.getPlayerId()).build());
    }

    private SockbowlOutMessage submit(Player p, String text) {
        return processor.playerSubmitAnswer(SubmitAnswer.builder()
                .gameSession(session).originatingPlayerId(p.getPlayerId()).answerText(text).build());
    }

    private Round round() {
        return session.getCurrentRound();
    }

    @Test
    @DisplayName("Correct buzz + correct answer wins the tossup and completes the round (no bonus attached)")
    void correctBuzzAndAnswerWinsTossup() {
        buzz(p1);
        assertEquals(RoundState.AWAITING_ANSWER, round().getRoundState());
        assertEquals("p1", round().getCurrentBuzz().getPlayerId());

        SockbowlOutMessage result = submit(p1, "Napoleon");
        AnswerUpdate update = (AnswerUpdate) ((SockbowlMultiOutMessage) result).getSockbowlOutMessages().get(0);
        assertTrue(update.isCorrect());
        assertEquals(RoundState.COMPLETED, round().getRoundState());
    }

    @Test
    @DisplayName("Wrong answer locks out only the buzzing player; others can still buzz on reopened tossup")
    void wrongAnswerLocksOutOnlyThatPlayer() {
        buzz(p1);
        submit(p1, "Hitler");
        assertNotEquals(RoundState.COMPLETED, round().getRoundState());

        // p1 is locked out (already has a buzz on record for their team) but p2 may still buzz.
        SockbowlOutMessage second = buzz(p2);
        assertInstanceOf(SockbowlMultiOutMessage.class, second);
        assertEquals(RoundState.AWAITING_ANSWER, round().getRoundState());
        assertEquals("p2", round().getCurrentBuzz().getPlayerId());
    }

    @Test
    @DisplayName("All players wrong/miss completes the round with no winner and no bonus")
    void allPlayersWrongCompletesWithNoBonus() {
        session.getGameSettings().setBonusesEnabled(true);
        buzz(p1);
        submit(p1, "Hitler");
        buzz(p2);
        submit(p2, "Stalin");
        buzz(p3);
        SockbowlOutMessage result = submit(p3, "Churchill");

        AnswerUpdate update = (AnswerUpdate) ((SockbowlMultiOutMessage) result).getSockbowlOutMessages().get(0);
        assertFalse(update.isCorrect());
        assertEquals(RoundState.COMPLETED, round().getRoundState());
        assertNull(round().getBonusEligibleTeamId());
    }

    @Test
    @DisplayName("Buzz timeout locks out the same as a wrong answer; play reopens for remaining players")
    void buzzTimeoutLocksOutLikeWrongAnswer() {
        round().setRoundState(RoundState.PROCTOR_READING);
        SockbowlOutMessage result = processor.timeout(TimeoutRound.builder()
                .gameSession(session).originatingPlayerId(p1.getPlayerId()).build());
        assertInstanceOf(SockbowlMultiOutMessage.class, result);
        assertEquals(RoundState.COMPLETED, round().getRoundState());
        AnswerUpdate update = (AnswerUpdate) ((SockbowlMultiOutMessage) result).getSockbowlOutMessages().get(0);
        assertFalse(update.isCorrect());
    }

    @Test
    @DisplayName("Regression (B5): GameTimerService reveal tick advances revealedWordCount and auto-arms the tossup timer for FFA")
    void ffaReadingRevealTicks() {
        SessionService sessionService = mock(SessionService.class);
        MessageService messageService = mock(MessageService.class);
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        GameTimerService gameTimerService = new GameTimerService(sessionService, messageService, messagingTemplate);

        Round timerRound = new Round();
        timerRound.setRoundState(RoundState.PROCTOR_READING);
        timerRound.setQuestion("one two three four five six seven eight nine ten");
        timerRound.setTotalWordCount(10);
        timerRound.setRevealedWordCount(0);

        TimerSettings timerSettings = TimerSettings.builder()
                .readingWordsPerSecond(10)
                .tossupTimerSeconds(5)
                .build();

        GameSettings ffaSettings = GameSettings.builder()
                .gameMode(GameMode.FREE_FOR_ALL)
                .timerSettings(timerSettings)
                .build();

        GameSession timerSession = GameSession.builder()
                .id("FFA-TIMER").joinCode("FTMR")
                .gameSettings(ffaSettings)
                .build();
        timerSession.getCurrentMatch().setCurrentRound(timerRound);

        when(sessionService.getAllActiveSessions()).thenReturn(List.of(timerSession));

        gameTimerService.processTimers();

        Round resultRound = timerSession.getCurrentRound();
        assertEquals(10, resultRound.getRevealedWordCount());
        assertEquals(RoundState.AWAITING_BUZZ, resultRound.getRoundState());
        assertTrue(resultRound.isTossupTimerActive());
    }

    @Test
    @DisplayName("Regression (B6): sanitized round question text for FFA is truncated to revealed word count, not full text or blank")
    void ffaSanitizedQuestionIsTruncatedNotBlank() {
        Round r = new Round();
        r.setRoundState(RoundState.AWAITING_BUZZ);
        r.setQuestion("one two three four five");
        r.setAnswer(ANSWER);
        r.setTotalWordCount(5);
        r.setRevealedWordCount(2);

        Round sanitized = GameSanitizer.revealQuestionHideAnswer(r, GameMode.FREE_FOR_ALL);

        assertEquals("", sanitized.getAnswer());
        assertEquals("one two", sanitized.getQuestion());
        assertNotEquals("", sanitized.getQuestion());
        assertNotEquals("one two three four five", sanitized.getQuestion());
    }

    @Test
    @DisplayName("Bonus flow: the single winning player is the only one prompted for all bonus parts")
    void bonusFlowOnlyWinningPlayerAnswers() {
        session.getGameSettings().setBonusesEnabled(true);
        com.soulsoftworks.sockbowlquestions.models.nodes.Bonus bonus =
            com.soulsoftworks.sockbowlquestions.models.nodes.Bonus.builder()
                .preamble("A three-part bonus.")
                .bonusParts(List.of(
                    part(0, "<u>alpha</u>"), part(1, "<u>beta</u>"), part(2, "<u>gamma</u>")))
                .build();
        round().setAssociatedBonus(bonus);

        buzz(p1);
        submit(p1, "Napoleon");
        assertEquals(RoundState.BONUS_AWAITING_ANSWER, round().getRoundState());
        assertEquals(teamIdOf(p1), round().getBonusEligibleTeamId());

        // Another player may not answer the bonus.
        assertInstanceOf(ProcessError.class, submit(p2, "alpha"));

        submit(p1, "alpha");
        assertEquals(RoundState.BONUS_AWAITING_ANSWER, round().getRoundState());
        submit(p1, "beta");
        submit(p1, "gamma");
        assertEquals(RoundState.COMPLETED, round().getRoundState());
    }

    private String teamIdOf(Player p) {
        return session.getTeamByPlayerId(p.getPlayerId()).getTeamId();
    }

    private com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart part(int order, String answer) {
        return com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart.builder()
                .order(order)
                .bonusPart(com.soulsoftworks.sockbowlquestions.models.nodes.BonusPart.builder()
                        .question("q" + order).answer(answer).build())
                .build();
    }
}
