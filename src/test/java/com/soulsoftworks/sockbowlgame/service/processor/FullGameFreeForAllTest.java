package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.model.socket.in.game.AdvanceRound;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.PlayerIncomingBuzz;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.StartBonus;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.SubmitAnswer;
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
 * Full-game (multi-round, packet-to-match-completion) coverage for FREE_FOR_ALL —
 * every player is their own team, same auto-judged flow as AUTO_PROCTOR. Drives a whole
 * match end to end across three solo players: StartMatch -> N rounds (buzz -> judge
 * [-> bonus]) -> AdvanceRound (owner-driven) -> MatchState.COMPLETED.
 */
class FullGameFreeForAllTest {

    private GameMessageProcessor gameProcessor;
    private ProgressionMessageProcessor progressionProcessor;
    private GameSession session;
    private Player p1; // solo team, owner
    private Player p2; // solo team
    private Player p3; // solo team

    @BeforeEach
    void setup() {
        gameProcessor = new GameMessageProcessor();
        progressionProcessor = new ProgressionMessageProcessor();

        p1 = Player.builder().playerId("p1").name("one").playerMode(PlayerMode.BUZZER).isGameOwner(true).build();
        p2 = Player.builder().playerId("p2").name("two").playerMode(PlayerMode.BUZZER).build();
        p3 = Player.builder().playerId("p3").name("three").playerMode(PlayerMode.BUZZER).build();

        List<Team> teams = createTeams(3);
        teams.get(0).addPlayerToTeam(p1);
        teams.get(1).addPlayerToTeam(p2);
        teams.get(2).addPlayerToTeam(p3);

        GameSettings settings = new GameSettings();
        settings.setGameMode(GameMode.FREE_FOR_ALL);

        session = GameSession.builder()
                .id("FFA").joinCode("FREE1")
                .playerList(List.of(p1, p2, p3)).teamList(teams)
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
        @DisplayName("Two-tossup match: p2 wins tossup 1, p3 wins tossup 2 after p1 and p2 miss, then match completes")
        void fullMatchNoBonuses() {
            session.getCurrentMatch().setPacket(twoTossupPacket(false, false));
            startMatch();
            assertEquals(1, round().getRoundNumber());

            buzz(p2);
            submit(p2, "Napoleon");
            assertEquals(RoundState.COMPLETED, round().getRoundState());

            advance(p1);
            assertEquals(2, round().getRoundNumber());

            buzz(p1);
            submit(p1, "wrong");
            buzz(p2);
            submit(p2, "also wrong");
            buzz(p3);
            SockbowlOutMessage result = submit(p3, "Shakespeare");
            assertNotNull(result);
            assertEquals(RoundState.COMPLETED, round().getRoundState());

            SockbowlOutMessage finalAdvance = advance(p1);
            assertNotNull(finalAdvance);
            assertEquals(MatchState.COMPLETED, session.getCurrentMatch().getMatchState());
        }

        @Test
        @DisplayName("All three players miss: round completes with no winner and no bonus, match still finishes")
        void allPlayersMissThenMatchCompletes() {
            session.getCurrentMatch().setPacket(twoTossupPacket(false, false));
            startMatch();

            buzz(p1);
            submit(p1, "Hitler");
            buzz(p2);
            submit(p2, "Stalin");
            buzz(p3);
            submit(p3, "Churchill");

            assertEquals(RoundState.COMPLETED, round().getRoundState());
            assertNull(round().getBonusEligibleTeamId());

            advance(p1);
            buzz(p1);
            submit(p1, "Shakespeare");
            advance(p1);

            assertEquals(MatchState.COMPLETED, session.getCurrentMatch().getMatchState());
        }
    }

    @Nested
    @DisplayName("With bonuses")
    class WithBonuses {

        @Test
        @DisplayName("Two-tossup match, both with bonuses: only the winning solo player answers each bonus")
        void fullMatchWithBonuses() {
            session.getGameSettings().setBonusesEnabled(true);
            session.getCurrentMatch().setPacket(twoTossupPacket(true, true));
            startMatch();

            // Round 1: p2 wins the tossup and 1/3 of the bonus.
            buzz(p2);
            submit(p2, "Napoleon");
            assertEquals(RoundState.BONUS_PENDING, round().getRoundState());
            assertEquals(teamIdOf(p2), round().getBonusEligibleTeamId());

            startBonus(p2);
            // Others are not the eligible team and cannot answer.
            assertInstanceOf(com.soulsoftworks.sockbowlgame.model.socket.out.error.ProcessError.class,
                    submit(p1, "alpha"));
            submit(p2, "alpha");
            submit(p2, "wrong");
            submit(p2, "wrong");
            assertEquals(RoundState.COMPLETED, round().getRoundState());
            assertEquals(10, round().getBonusPoints());

            advance(p1);

            // Round 2: p3 wins the tossup and sweeps the bonus.
            buzz(p3);
            submit(p3, "Shakespeare");
            assertEquals(RoundState.BONUS_PENDING, round().getRoundState());
            assertEquals(teamIdOf(p3), round().getBonusEligibleTeamId());

            startBonus(p3);
            submit(p3, "alpha");
            submit(p3, "beta");
            submit(p3, "gamma");
            assertEquals(RoundState.COMPLETED, round().getRoundState());
            assertEquals(30, round().getBonusPoints());

            SockbowlOutMessage result = advance(p1);
            assertNotNull(result);
            assertEquals(MatchState.COMPLETED, session.getCurrentMatch().getMatchState());
        }

        @Test
        @DisplayName("Bonus data present but bonusesEnabled=false: round completes straight through")
        void bonusDataPresentButFeatureDisabled() {
            session.getGameSettings().setBonusesEnabled(false);
            session.getCurrentMatch().setPacket(twoTossupPacket(true, false));
            startMatch();

            buzz(p1);
            submit(p1, "Napoleon");
            assertEquals(RoundState.COMPLETED, round().getRoundState());
        }
    }

    private String teamIdOf(Player p) {
        return session.getTeamByPlayerId(p.getPlayerId()).getTeamId();
    }
}
