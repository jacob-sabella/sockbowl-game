package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.model.socket.in.game.*;
import com.soulsoftworks.sockbowlgame.model.socket.in.progression.StartMatch;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.state.*;
import com.soulsoftworks.sockbowlgame.util.PacketBuilderHelper;
import com.soulsoftworks.sockbowlquestions.models.nodes.Bonus;
import com.soulsoftworks.sockbowlquestions.models.nodes.BonusPart;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsBonus;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsTossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.soulsoftworks.sockbowlgame.service.processor.MatchContextUtils.createTeams;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Full-game (multi-round, packet-to-match-completion) coverage for QUIZ_BOWL_CLASSIC —
 * the human-proctor mode. Drives a whole match end to end via the real message
 * processors: StartMatch -> N rounds (reading -> buzz -> proctor judgement [-> bonus]) ->
 * AdvanceRound -> MatchState.COMPLETED.
 */
class FullGameQuizBowlClassicTest {

    private GameMessageProcessor gameProcessor;
    private ProgressionMessageProcessor progressionProcessor;
    private GameSession session;
    private Player proctor;
    private Player p1; // team 0
    private Player p2; // team 1

    @BeforeEach
    void setup() {
        gameProcessor = new GameMessageProcessor();
        progressionProcessor = new ProgressionMessageProcessor();

        proctor = Player.builder().playerId("proctor").name("proctor")
                .playerMode(PlayerMode.PROCTOR).isGameOwner(true).build();
        p1 = Player.builder().playerId("p1").name("one").playerMode(PlayerMode.BUZZER).build();
        p2 = Player.builder().playerId("p2").name("two").playerMode(PlayerMode.BUZZER).build();

        List<Team> teams = createTeams(2);
        teams.get(0).addPlayerToTeam(p1);
        teams.get(1).addPlayerToTeam(p2);

        GameSettings settings = GameSettings.builder()
                .gameMode(GameMode.QUIZ_BOWL_CLASSIC)
                .proctorType(ProctorType.ONLINE_PROCTOR)
                .build();

        session = GameSession.builder()
                .id("QBC").joinCode("QBC1")
                .playerList(List.of(proctor, p1, p2))
                .teamList(teams)
                .currentMatch(new Match())
                .gameSettings(settings)
                .build();
    }

    private Packet twoTossupPacket(boolean withBonusOnFirst, boolean withBonusOnSecond) {
        List<ContainsTossup> tossups = new ArrayList<>();
        tossups.add(PacketBuilderHelper.createTossup(1, 0,
                Tossup.builder().question("This French emperor lost at Waterloo.")
                        .answer("<b><u>Napoleon</u></b> Bonaparte").build()));
        tossups.add(PacketBuilderHelper.createTossup(2, 1,
                Tossup.builder().question("This English playwright wrote Hamlet.")
                        .answer("<b><u>Shakespeare</u></b>").build()));

        List<ContainsBonus> bonuses = new ArrayList<>();
        bonuses.add(PacketBuilderHelper.createBonus(1, 0, withBonusOnFirst ? threePartBonus() : null));
        bonuses.add(PacketBuilderHelper.createBonus(2, 1, withBonusOnSecond ? threePartBonus() : null));

        return PacketBuilderHelper.createPacket("PKT", "Test Packet",
                PacketBuilderHelper.createDifficulty("D1", "Regionals"), tossups, bonuses);
    }

    private Bonus threePartBonus() {
        return Bonus.builder()
                .preamble("A three-part bonus.")
                .bonusParts(List.of(
                        part(0, "<u>alpha</u>"), part(1, "<u>beta</u>"), part(2, "<u>gamma</u>")))
                .build();
    }

    private HasBonusPart part(int order, String answer) {
        return HasBonusPart.builder()
                .order(order)
                .bonusPart(BonusPart.builder().question("q" + order).answer(answer).build())
                .build();
    }

    private SockbowlOutMessage startMatch() {
        return progressionProcessor.startMatch(StartMatch.builder()
                .gameSession(session).originatingPlayerId(proctor.getPlayerId()).build());
    }

    private SockbowlOutMessage finishedReading() {
        return gameProcessor.finishedReading(FinishedReading.builder()
                .gameSession(session).originatingPlayerId(proctor.getPlayerId()).build());
    }

    private SockbowlOutMessage buzz(Player p) {
        return gameProcessor.playerBuzz(PlayerIncomingBuzz.builder()
                .gameSession(session).originatingPlayerId(p.getPlayerId()).build());
    }

    private SockbowlOutMessage judge(boolean correct) {
        return gameProcessor.playerAnswer(AnswerOutcome.builder()
                .gameSession(session).originatingPlayerId(proctor.getPlayerId()).correct(correct).build());
    }

    private SockbowlOutMessage advance(Player who) {
        return gameProcessor.advanceRound(AdvanceRound.builder()
                .gameSession(session).originatingPlayerId(who.getPlayerId()).build());
    }

    private SockbowlOutMessage finishedReadingBonusPreamble() {
        return gameProcessor.finishedReadingBonusPreamble(FinishedReadingBonusPreamble.builder()
                .gameSession(session).originatingPlayerId(proctor.getPlayerId()).build());
    }

    private SockbowlOutMessage finishedReadingBonusPart() {
        return gameProcessor.finishedReadingBonusPart(FinishedReadingBonusPart.builder()
                .gameSession(session).originatingPlayerId(proctor.getPlayerId()).build());
    }

    private SockbowlOutMessage bonusPartAnswer(int idx, boolean correct) {
        return gameProcessor.bonusPartAnswer(BonusPartOutcome.builder()
                .gameSession(session).originatingPlayerId(proctor.getPlayerId())
                .partIndex(idx).correct(correct).build());
    }

    private Round round() {
        return session.getCurrentRound();
    }

    /** Reads the preamble and all three bonus parts, judging each answer as given. */
    private void runBonus(boolean... correctness) {
        finishedReadingBonusPreamble();
        for (int i = 0; i < correctness.length; i++) {
            finishedReadingBonusPart();
            bonusPartAnswer(i, correctness[i]);
        }
    }

    @Nested
    @DisplayName("Without bonuses")
    class WithoutBonuses {

        @Test
        @DisplayName("Two-tossup match: first team wins tossup 1 clean, second team wins tossup 2 after a miss, then match completes")
        void fullMatchNoBonuses() {
            session.getCurrentMatch().setPacket(twoTossupPacket(false, false));

            assertNotNull(startMatch());
            assertEquals(MatchState.IN_GAME, session.getCurrentMatch().getMatchState());
            assertEquals(1, round().getRoundNumber());

            // Round 1: p1 buzzes and answers correctly.
            finishedReading();
            buzz(p1);
            judge(true);
            assertEquals(RoundState.COMPLETED, round().getRoundState());

            advance(proctor);
            assertEquals(2, round().getRoundNumber());
            assertEquals(RoundState.PROCTOR_READING, round().getRoundState());

            // Round 2: p1 buzzes and misses; the question was already fully read, so play
            // returns straight to AWAITING_BUZZ (not another read-through) for p2 to win it.
            finishedReading();
            buzz(p1);
            judge(false);
            assertEquals(RoundState.AWAITING_BUZZ, round().getRoundState());

            buzz(p2);
            judge(true);
            assertEquals(RoundState.COMPLETED, round().getRoundState());
            assertEquals(1, session.getCurrentMatch().getPreviousRounds().size());

            // Advancing past the last tossup completes the match.
            SockbowlOutMessage result = advance(proctor);
            assertNotNull(result);
            assertEquals(MatchState.COMPLETED, session.getCurrentMatch().getMatchState());
            assertNull(session.getCurrentMatch().getCurrentRound());
            assertEquals(2, session.getCurrentMatch().getPreviousRounds().size());
        }

        @Test
        @DisplayName("Both teams miss a tossup: round completes with no winner, no bonus, and the match still finishes")
        void bothTeamsMissTossup() {
            session.getCurrentMatch().setPacket(twoTossupPacket(false, false));
            startMatch();

            finishedReading();
            buzz(p1);
            judge(false);
            assertEquals(RoundState.AWAITING_BUZZ, round().getRoundState());

            buzz(p2);
            SockbowlOutMessage result = judge(false);
            assertNotNull(result);
            assertEquals(RoundState.COMPLETED, round().getRoundState());

            advance(proctor);
            finishedReading();
            buzz(p1);
            judge(true);
            advance(proctor);
            assertEquals(MatchState.COMPLETED, session.getCurrentMatch().getMatchState());
        }
    }

    @Nested
    @DisplayName("With bonuses")
    class WithBonuses {

        @Test
        @DisplayName("Two-tossup match, both with bonuses: mixed bonus results on each, then match completes")
        void fullMatchWithBonuses() {
            session.getGameSettings().setBonusesEnabled(true);
            session.getCurrentMatch().setPacket(twoTossupPacket(true, true));
            startMatch();

            // Round 1: p1 wins the tossup, goes 2/3 on the bonus.
            finishedReading();
            buzz(p1);
            judge(true);
            assertEquals(RoundState.BONUS_READING_PREAMBLE, round().getRoundState());

            runBonus(true, true, false);
            assertEquals(RoundState.COMPLETED, round().getRoundState());
            assertEquals(20, round().getBonusPoints());

            advance(proctor);
            assertEquals(2, round().getRoundNumber());

            // Round 2: p2 wins the tossup, goes 0/3 on the bonus.
            finishedReading();
            buzz(p2);
            judge(true);
            assertEquals(RoundState.BONUS_READING_PREAMBLE, round().getRoundState());

            runBonus(false, false, false);
            assertEquals(RoundState.COMPLETED, round().getRoundState());
            assertEquals(0, round().getBonusPoints());

            SockbowlOutMessage result = advance(proctor);
            assertNotNull(result);
            assertEquals(MatchState.COMPLETED, session.getCurrentMatch().getMatchState());
        }

        @Test
        @DisplayName("Bonus enabled but this tossup has no associated bonus: round completes straight through")
        void bonusEnabledButNoBonusOnThisTossupSkipsStraightToCompleted() {
            session.getGameSettings().setBonusesEnabled(true);
            session.getCurrentMatch().setPacket(twoTossupPacket(false, false));
            startMatch();

            finishedReading();
            buzz(p1);
            judge(true);

            assertEquals(RoundState.COMPLETED, round().getRoundState());
        }

        @Test
        @DisplayName("Bonus data exists on the tossup but bonusesEnabled=false: round completes without entering the bonus")
        void bonusDataPresentButFeatureDisabledSkipsBonus() {
            session.getGameSettings().setBonusesEnabled(false);
            session.getCurrentMatch().setPacket(twoTossupPacket(true, false));
            startMatch();

            finishedReading();
            buzz(p1);
            judge(true);

            assertEquals(RoundState.COMPLETED, round().getRoundState());
        }

        @Test
        @DisplayName("A wrong tossup answer never enters the bonus, even when one is attached")
        void wrongTossupNeverEntersBonus() {
            session.getGameSettings().setBonusesEnabled(true);
            session.getCurrentMatch().setPacket(twoTossupPacket(true, false));
            startMatch();

            finishedReading();
            buzz(p1);
            judge(false);
            assertEquals(RoundState.AWAITING_BUZZ, round().getRoundState());

            buzz(p2);
            judge(false);
            assertEquals(RoundState.COMPLETED, round().getRoundState());
            assertNull(round().getCurrentBonus());
        }
    }
}
