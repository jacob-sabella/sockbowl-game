package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.model.socket.in.game.PlayerIncomingBuzz;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.SubmitAnswer;
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
        assertEquals(RoundState.BONUS_AWAITING_ANSWER, round().getRoundState());
        assertEquals("TEST-TEAM-1", round().getBonusEligibleTeamId());

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
        assertInstanceOf(ProcessError.class, submit(p2, "alpha"));
    }
}
