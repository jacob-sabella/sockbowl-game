package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.model.socket.in.game.PlayerIncomingBuzz;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.StartBonus;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.SubmitAnswer;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.TimeoutRound;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.ProcessError;
import com.soulsoftworks.sockbowlgame.model.socket.out.game.AnswerUpdate;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlMultiOutMessage;
import com.soulsoftworks.sockbowlgame.model.state.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.soulsoftworks.sockbowlgame.service.processor.MatchContextUtils.createTeams;
import static org.junit.jupiter.api.Assertions.*;

/** In-memory tests for the auto-proctor multiplayer tossup flow (buzz → auto-judged answer). */
class AutoProctorTossupTest {

    private GameMessageProcessor processor;
    private GameSession session;
    private Player p1; // team 0
    private Player p2; // team 1

    private static final String ANSWER = "<b><u>Napoleon</u></b> Bonaparte";

    @BeforeEach
    void setup() {
        processor = new GameMessageProcessor();

        p1 = Player.builder().playerId("p1").name("one").playerMode(PlayerMode.BUZZER).isGameOwner(true).build();
        p2 = Player.builder().playerId("p2").name("two").playerMode(PlayerMode.BUZZER).build();

        List<Team> teams = createTeams(2);
        teams.get(0).addPlayerToTeam(p1);
        teams.get(1).addPlayerToTeam(p2);

        GameSettings settings = new GameSettings();
        settings.setGameMode(GameMode.AUTO_PROCTOR);

        Round round = new Round();
        round.setRoundState(RoundState.AWAITING_BUZZ);
        round.setAnswer(ANSWER);

        Match match = new Match();
        match.setMatchState(MatchState.IN_GAME);
        match.setCurrentRound(round);

        session = GameSession.builder()
                .id("AP").joinCode("AUTO")
                .playerList(List.of(p1, p2)).teamList(teams)
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

    private SockbowlOutMessage startBonus(Player p) {
        return processor.startBonus(StartBonus.builder()
                .gameSession(session).originatingPlayerId(p.getPlayerId()).build());
    }

    private Round round() {
        return session.getCurrentRound();
    }

    @Test
    @DisplayName("Buzz locks the round to AWAITING_ANSWER and broadcasts (no proctor NPE)")
    void buzzLocksRound() {
        SockbowlOutMessage result = buzz(p1);
        assertInstanceOf(SockbowlMultiOutMessage.class, result);
        assertEquals(RoundState.AWAITING_ANSWER, round().getRoundState());
        assertEquals("p1", round().getCurrentBuzz().getPlayerId());
    }

    @Test
    @DisplayName("Correct answer from the buzzer scores and completes the round")
    void correctCompletes() {
        buzz(p1);
        SockbowlOutMessage result = submit(p1, "Napoleon");
        AnswerUpdate update = (AnswerUpdate) ((SockbowlMultiOutMessage) result).getSockbowlOutMessages().get(0);
        assertTrue(update.isCorrect());
        assertEquals(RoundState.COMPLETED, round().getRoundState());
    }

    @Test
    @DisplayName("Wrong answer returns play to the other team; they can still win it")
    void wrongThenOtherTeamWins() {
        buzz(p1);
        submit(p1, "Hitler");
        // Not completed — team 1 hasn't answered yet.
        assertNotEquals(RoundState.COMPLETED, round().getRoundState());

        SockbowlOutMessage second = buzz(p2);
        assertEquals(RoundState.AWAITING_ANSWER, round().getRoundState());
        AnswerUpdate update = (AnswerUpdate) ((SockbowlMultiOutMessage) submit(p2, "Napoleon"))
                .getSockbowlOutMessages().get(0);
        assertTrue(update.isCorrect());
        assertEquals(RoundState.COMPLETED, round().getRoundState());
    }

    @Test
    @DisplayName("Only the buzzed-in player may answer")
    void onlyBuzzerAnswers() {
        buzz(p1);
        assertInstanceOf(ProcessError.class, submit(p2, "Napoleon"));
    }

    @Test
    @DisplayName("Cannot answer before anyone has buzzed")
    void noAnswerBeforeBuzz() {
        assertInstanceOf(ProcessError.class, submit(p1, "Napoleon"));
    }

    /* ------------------------------- bonuses ------------------------------ */

    private void attachBonus() {
        session.getGameSettings().setBonusesEnabled(true);
        com.soulsoftworks.sockbowlquestions.models.nodes.Bonus bonus =
            com.soulsoftworks.sockbowlquestions.models.nodes.Bonus.builder()
                .preamble("A three-part bonus.")
                .bonusParts(List.of(
                    part(0, "<u>alpha</u>"), part(1, "<u>beta</u>"), part(2, "<u>gamma</u>")))
                .build();
        round().setAssociatedBonus(bonus);
    }

    private com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart part(int order, String answer) {
        return com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart.builder()
                .order(order)
                .bonusPart(com.soulsoftworks.sockbowlquestions.models.nodes.BonusPart.builder()
                        .question("q" + order).answer(answer).build())
                .build();
    }

    @Test
    @DisplayName("Correct tossup enters the bonus for the winning team; 3 parts complete the round")
    void bonusFlow() {
        attachBonus();
        buzz(p1);
        submit(p1, "Napoleon");
        assertEquals(RoundState.BONUS_PENDING, round().getRoundState());
        assertEquals("TEST-TEAM-1", round().getBonusEligibleTeamId());

        startBonus(p1);
        assertEquals(RoundState.BONUS_AWAITING_ANSWER, round().getRoundState());
        assertEquals(0, round().getCurrentBonusPartIndex());

        submit(p1, "alpha");
        assertEquals(RoundState.BONUS_AWAITING_ANSWER, round().getRoundState());
        submit(p1, "beta");
        submit(p1, "gamma");
        assertEquals(RoundState.COMPLETED, round().getRoundState());
    }

    @Test
    @DisplayName("Only the team that won the tossup may answer the bonus")
    void onlyEligibleTeamAnswersBonus() {
        attachBonus();
        buzz(p1);
        submit(p1, "Napoleon");
        startBonus(p1);
        assertInstanceOf(ProcessError.class, submit(p2, "alpha"));
    }

    @Test
    @DisplayName("StartBonus from a player neither on the eligible team nor the owner is rejected")
    void startBonusFromNonEligibleNonOwnerRejected() {
        attachBonus();
        buzz(p1);
        submit(p1, "Napoleon");
        assertEquals(RoundState.BONUS_PENDING, round().getRoundState());

        assertInstanceOf(ProcessError.class, startBonus(p2));
        assertEquals(RoundState.BONUS_PENDING, round().getRoundState());
    }

    @Test
    @DisplayName("The game owner may start the bonus even when not on the eligible team")
    void ownerMayStartBonusEvenIfNotOnEligibleTeam() {
        attachBonus();
        buzz(p2);
        submit(p2, "Napoleon");
        assertEquals("TEST-TEAM-2", round().getBonusEligibleTeamId());

        SockbowlOutMessage result = startBonus(p1);
        assertInstanceOf(SockbowlMultiOutMessage.class, result);
        assertEquals(RoundState.BONUS_AWAITING_ANSWER, round().getRoundState());
    }

    @Test
    @DisplayName("StartBonus is idempotent once the bonus has already started")
    void startBonusIdempotentWhenAlreadyStarted() {
        attachBonus();
        buzz(p1);
        submit(p1, "Napoleon");
        startBonus(p1);
        assertEquals(RoundState.BONUS_AWAITING_ANSWER, round().getRoundState());

        SockbowlOutMessage result = startBonus(p1);
        assertInstanceOf(SockbowlMultiOutMessage.class, result);
        assertEquals(RoundState.BONUS_AWAITING_ANSWER, round().getRoundState());
    }

    @Test
    @DisplayName("StartBonus is rejected outside BONUS_PENDING")
    void startBonusRejectedOutsideBonusPending() {
        round().setRoundState(RoundState.AWAITING_BUZZ);
        assertInstanceOf(ProcessError.class, startBonus(p1));
    }

    /* ------------------------------- buzz timeout (proctorless) ------------------------------- */

    private SockbowlOutMessage timeout(Player p) {
        return processor.timeout(TimeoutRound.builder()
                .gameSession(session).originatingPlayerId(p.getPlayerId()).build());
    }

    @Test
    @DisplayName("Owner's TimeoutRound completes the round when nobody buzzed (still PROCTOR_READING)")
    void ownerTimeoutCompletesRoundWhileStillReading() {
        round().setRoundState(RoundState.PROCTOR_READING);
        SockbowlOutMessage result = timeout(p1);
        assertInstanceOf(SockbowlMultiOutMessage.class, result);
        assertEquals(RoundState.COMPLETED, round().getRoundState());
        AnswerUpdate update = (AnswerUpdate) ((SockbowlMultiOutMessage) result).getSockbowlOutMessages().get(0);
        assertFalse(update.isCorrect());
    }

    @Test
    @DisplayName("Non-owner's TimeoutRound is rejected; round stays unchanged")
    void nonOwnerTimeoutRejected() {
        round().setRoundState(RoundState.PROCTOR_READING);
        SockbowlOutMessage result = timeout(p2);
        assertInstanceOf(ProcessError.class, result);
        assertEquals(RoundState.PROCTOR_READING, round().getRoundState());
    }

    @Test
    @DisplayName("Classic (non-proctorless) mode: a non-proctor's TimeoutRound is still rejected")
    void classicModeNonProctorTimeoutRejected() {
        session.getGameSettings().setGameMode(GameMode.QUIZ_BOWL_CLASSIC);
        round().setRoundState(RoundState.AWAITING_BUZZ);
        SockbowlOutMessage result = timeout(p1);
        assertInstanceOf(ProcessError.class, result);
        assertEquals(RoundState.AWAITING_BUZZ, round().getRoundState());
    }
}
