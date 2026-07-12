package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.model.socket.in.game.PlayerIncomingBuzz;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.StartBonus;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.SubmitAnswer;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.TimeoutRound;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.AdvanceRound;
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
 * Full-game (multi-round, packet-to-match-completion) coverage for AUTO_PROCTOR —
 * multiplayer with teams + buzzers, judged by {@link com.soulsoftworks.sockbowlgame.judge.AnswerJudgeService}
 * instead of a human proctor. Drives a whole match end to end: StartMatch -> N rounds
 * (buzz -> judge [-> bonus]) -> AdvanceRound (owner-driven, since there's no proctor) ->
 * MatchState.COMPLETED.
 */
class FullGameAutoProctorTest {

    private GameMessageProcessor gameProcessor;
    private ProgressionMessageProcessor progressionProcessor;
    private GameSession session;
    private Player p1; // team 0, owner
    private Player p2; // team 1

    @BeforeEach
    void setup() {
        gameProcessor = new GameMessageProcessor();
        progressionProcessor = new ProgressionMessageProcessor();

        p1 = Player.builder().playerId("p1").name("one").playerMode(PlayerMode.BUZZER).isGameOwner(true).build();
        p2 = Player.builder().playerId("p2").name("two").playerMode(PlayerMode.BUZZER).build();

        List<Team> teams = createTeams(2);
        teams.get(0).addPlayerToTeam(p1);
        teams.get(1).addPlayerToTeam(p2);

        GameSettings settings = new GameSettings();
        settings.setGameMode(GameMode.AUTO_PROCTOR);

        session = GameSession.builder()
                .id("AP").joinCode("AUTO")
                .playerList(List.of(p1, p2)).teamList(teams)
                .currentMatch(new Match()).gameSettings(settings).build();
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
                .gameSession(session).originatingPlayerId(p1.getPlayerId()).build());
    }

    private SockbowlOutMessage buzz(Player p) {
        return gameProcessor.playerBuzz(PlayerIncomingBuzz.builder()
                .gameSession(session).originatingPlayerId(p.getPlayerId()).build());
    }

    private SockbowlOutMessage submit(Player p, String text) {
        return gameProcessor.playerSubmitAnswer(SubmitAnswer.builder()
                .gameSession(session).originatingPlayerId(p.getPlayerId()).answerText(text).build());
    }

    private SockbowlOutMessage startBonus(Player p) {
        return gameProcessor.startBonus(StartBonus.builder()
                .gameSession(session).originatingPlayerId(p.getPlayerId()).build());
    }

    private SockbowlOutMessage timeout(Player p) {
        return gameProcessor.timeout(TimeoutRound.builder()
                .gameSession(session).originatingPlayerId(p.getPlayerId()).build());
    }

    private SockbowlOutMessage advance(Player p) {
        return gameProcessor.advanceRound(AdvanceRound.builder()
                .gameSession(session).originatingPlayerId(p.getPlayerId()).build());
    }

    private Round round() {
        return session.getCurrentRound();
    }

    @Nested
    @DisplayName("Without bonuses")
    class WithoutBonuses {

        @Test
        @DisplayName("Two-tossup match: p1 wins tossup 1, nobody buzzes on tossup 2 (owner times it out), then match completes")
        void fullMatchNoBonuses() {
            session.getCurrentMatch().setPacket(twoTossupPacket(false, false));
            startMatch();
            assertEquals(MatchState.IN_GAME, session.getCurrentMatch().getMatchState());
            assertEquals(1, round().getRoundNumber());

            buzz(p1);
            submit(p1, "Napoleon");
            assertEquals(RoundState.COMPLETED, round().getRoundState());

            advance(p1);
            assertEquals(2, round().getRoundNumber());
            assertEquals(RoundState.PROCTOR_READING, round().getRoundState());

            // Nobody buzzes — owner forces the round closed.
            SockbowlOutMessage result = timeout(p1);
            assertNotNull(result);
            assertEquals(RoundState.COMPLETED, round().getRoundState());

            SockbowlOutMessage finalAdvance = advance(p1);
            assertNotNull(finalAdvance);
            assertEquals(MatchState.COMPLETED, session.getCurrentMatch().getMatchState());
            assertNull(session.getCurrentMatch().getCurrentRound());
        }

        @Test
        @DisplayName("Both teams miss the tossup: round completes with no winner, no bonus started")
        void bothTeamsMiss() {
            session.getCurrentMatch().setPacket(twoTossupPacket(false, false));
            startMatch();

            buzz(p1);
            submit(p1, "Hitler");
            assertNotEquals(RoundState.COMPLETED, round().getRoundState());

            buzz(p2);
            submit(p2, "Stalin");
            assertEquals(RoundState.COMPLETED, round().getRoundState());
            assertNull(round().getBonusEligibleTeamId());

            advance(p1);
            assertEquals(MatchState.IN_GAME, session.getCurrentMatch().getMatchState());
        }
    }

    @Nested
    @DisplayName("With bonuses")
    class WithBonuses {

        @Test
        @DisplayName("Two-tossup match, both with bonuses: p1 sweeps the first bonus, p2 whiffs the second, then match completes")
        void fullMatchWithBonuses() {
            session.getGameSettings().setBonusesEnabled(true);
            session.getCurrentMatch().setPacket(twoTossupPacket(true, true));
            startMatch();

            // Round 1: p1 wins the tossup and the whole bonus.
            buzz(p1);
            submit(p1, "Napoleon");
            assertEquals(RoundState.BONUS_PENDING, round().getRoundState());
            assertEquals("TEST-TEAM-1", round().getBonusEligibleTeamId());

            startBonus(p1);
            assertEquals(RoundState.BONUS_AWAITING_ANSWER, round().getRoundState());
            submit(p1, "alpha");
            submit(p1, "beta");
            submit(p1, "gamma");
            assertEquals(RoundState.COMPLETED, round().getRoundState());
            assertEquals(30, round().getBonusPoints());

            advance(p1);
            assertEquals(2, round().getRoundNumber());

            // Round 2: p2 wins the tossup but whiffs the whole bonus.
            buzz(p2);
            submit(p2, "Shakespeare");
            assertEquals(RoundState.BONUS_PENDING, round().getRoundState());
            assertEquals("TEST-TEAM-2", round().getBonusEligibleTeamId());

            startBonus(p2);
            submit(p2, "wrong one");
            submit(p2, "wrong two");
            submit(p2, "wrong three");
            assertEquals(RoundState.COMPLETED, round().getRoundState());
            assertEquals(0, round().getBonusPoints());

            SockbowlOutMessage result = advance(p1);
            assertNotNull(result);
            assertEquals(MatchState.COMPLETED, session.getCurrentMatch().getMatchState());
        }

        @Test
        @DisplayName("Only the eligible (winning) team can answer the bonus; the other team is rejected")
        void onlyEligibleTeamAnswersBonus() {
            session.getGameSettings().setBonusesEnabled(true);
            session.getCurrentMatch().setPacket(twoTossupPacket(true, false));
            startMatch();

            buzz(p1);
            submit(p1, "Napoleon");
            startBonus(p1);

            assertInstanceOf(com.soulsoftworks.sockbowlgame.model.socket.out.error.ProcessError.class,
                    submit(p2, "alpha"));
        }

        @Test
        @DisplayName("Bonus data present but bonusesEnabled=false: round completes straight through, no bonus phase")
        void bonusDataPresentButFeatureDisabled() {
            session.getGameSettings().setBonusesEnabled(false);
            session.getCurrentMatch().setPacket(twoTossupPacket(true, false));
            startMatch();

            buzz(p1);
            SockbowlOutMessage result = submit(p1, "Napoleon");
            assertNotNull(result);
            assertEquals(RoundState.COMPLETED, round().getRoundState());
        }

        @Test
        @DisplayName("A wrong tossup answer never enters the bonus, even when one is attached")
        void wrongTossupNeverEntersBonus() {
            session.getGameSettings().setBonusesEnabled(true);
            session.getCurrentMatch().setPacket(twoTossupPacket(true, false));
            startMatch();

            buzz(p1);
            submit(p1, "Hitler");
            buzz(p2);
            submit(p2, "Stalin");

            assertEquals(RoundState.COMPLETED, round().getRoundState());
            assertNull(round().getCurrentBonus());
        }
    }
}
