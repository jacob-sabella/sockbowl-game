package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.model.socket.in.game.AdvanceRound;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.SubmitAnswer;
import com.soulsoftworks.sockbowlgame.model.socket.in.progression.StartMatch;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlMultiOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.game.AnswerUpdate;
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
 * Full-game (multi-round, packet-to-match-completion) coverage for SINGLE_PLAYER — one
 * player vs. the packet, adjudicated by {@link com.soulsoftworks.sockbowlgame.judge.AnswerJudgeService}.
 * Drives a whole three-tossup match end to end: StartMatch -> submit-answer per round
 * (one guess resolves it, win or lose) -> AdvanceRound (owner-driven) -> MatchState.COMPLETED.
 */
class FullGameSinglePlayerTest {

    private GameMessageProcessor gameProcessor;
    private ProgressionMessageProcessor progressionProcessor;
    private GameSession session;
    private Player player;

    @BeforeEach
    void setup() {
        gameProcessor = new GameMessageProcessor();
        progressionProcessor = new ProgressionMessageProcessor();

        player = Player.builder().playerId("solo").name("solo")
                .playerMode(PlayerMode.BUZZER).isGameOwner(true).build();

        List<Team> teams = createTeams(1);
        teams.get(0).addPlayerToTeam(player);

        GameSettings settings = new GameSettings();
        settings.setGameMode(GameMode.SINGLE_PLAYER);

        session = GameSession.builder()
                .id("SP").joinCode("SOLO")
                .playerList(List.of(player)).teamList(teams)
                .currentMatch(new Match()).gameSettings(settings).build();
    }

    /** Three tossups; a bonus is attached to the middle one to prove single-player ignores it. */
    private Packet threeTossupPacketWithMiddleBonus() {
        List<ContainsTossup> tossups = new ArrayList<>();
        tossups.add(PacketBuilderHelper.createTossup(1, 0,
                Tossup.builder().question("This French emperor lost at Waterloo.")
                        .answer("<b><u>Napoleon</u></b> Bonaparte").build()));
        tossups.add(PacketBuilderHelper.createTossup(2, 1,
                Tossup.builder().question("This English playwright wrote Hamlet.")
                        .answer("<b><u>Shakespeare</u></b>").build()));
        tossups.add(PacketBuilderHelper.createTossup(3, 2,
                Tossup.builder().question("This physicist developed general relativity.")
                        .answer("<b><u>Einstein</u></b>").build()));

        List<ContainsBonus> bonuses = new ArrayList<>();
        bonuses.add(PacketBuilderHelper.createBonus(1, 0, null));
        bonuses.add(PacketBuilderHelper.createBonus(2, 1, Bonus.builder()
                .preamble("A three-part bonus.")
                .bonusParts(List.of(
                        part(0, "<u>alpha</u>"), part(1, "<u>beta</u>"), part(2, "<u>gamma</u>")))
                .build()));
        bonuses.add(PacketBuilderHelper.createBonus(3, 2, null));

        return PacketBuilderHelper.createPacket("PKT", "Test Packet",
                PacketBuilderHelper.createDifficulty("D1", "Regionals"), tossups, bonuses);
    }

    private HasBonusPart part(int order, String answer) {
        return HasBonusPart.builder()
                .order(order)
                .bonusPart(BonusPart.builder().question("q" + order).answer(answer).build())
                .build();
    }

    private SockbowlOutMessage startMatch() {
        return progressionProcessor.startMatch(StartMatch.builder()
                .gameSession(session).originatingPlayerId(player.getPlayerId()).build());
    }

    private SockbowlOutMessage submit(String text) {
        return gameProcessor.playerSubmitAnswer(SubmitAnswer.builder()
                .gameSession(session).originatingPlayerId(player.getPlayerId()).answerText(text).build());
    }

    private SockbowlOutMessage advance() {
        return gameProcessor.advanceRound(AdvanceRound.builder()
                .gameSession(session).originatingPlayerId(player.getPlayerId()).build());
    }

    private boolean isCorrect(SockbowlOutMessage result) {
        return ((AnswerUpdate) ((SockbowlMultiOutMessage) result).getSockbowlOutMessages().get(0)).isCorrect();
    }

    private Round round() {
        return session.getCurrentRound();
    }

    @Nested
    @DisplayName("Bonuses always disabled for single player")
    class BonusesForcedOff {

        @Test
        @DisplayName("Three-tossup match, mixed right/wrong/right: bonuses never trigger even with a bonus attached and the setting enabled")
        void fullMatchMixedResultsNeverEntersBonus() {
            // Even flipping the setting on and attaching real bonus data must not open a bonus phase.
            session.getGameSettings().setBonusesEnabled(true);
            session.getCurrentMatch().setPacket(threeTossupPacketWithMiddleBonus());

            startMatch();
            assertEquals(MatchState.IN_GAME, session.getCurrentMatch().getMatchState());
            assertEquals(1, round().getRoundNumber());

            // Round 1: correct.
            assertTrue(isCorrect(submit("Napoleon")));
            assertEquals(RoundState.COMPLETED, round().getRoundState());
            assertEquals(0, session.getCurrentMatch().getPreviousRounds().size());

            advance();
            assertEquals(2, round().getRoundNumber());

            // Round 2: this tossup HAS a bonus attached, but wrong answer never reaches it anyway.
            assertFalse(isCorrect(submit("nobody")));
            assertEquals(RoundState.COMPLETED, round().getRoundState());
            assertEquals(1, session.getCurrentMatch().getPreviousRounds().size());

            advance();
            assertEquals(3, round().getRoundNumber());

            // Round 3: correct again — still no bonus phase, straight to completed.
            assertTrue(isCorrect(submit("Einstein")));
            assertEquals(RoundState.COMPLETED, round().getRoundState());

            SockbowlOutMessage result = advance();
            assertNotNull(result);
            assertEquals(MatchState.COMPLETED, session.getCurrentMatch().getMatchState());
            assertNull(session.getCurrentMatch().getCurrentRound());
            assertEquals(3, session.getCurrentMatch().getPreviousRounds().size());
        }

        @Test
        @DisplayName("A wrong answer on the bonus-bearing tossup reveals the full answer line and completes the round with no bonus")
        void wrongAnswerOnBonusTossupRevealsAnswerNoBonus() {
            session.getGameSettings().setBonusesEnabled(true);
            session.getCurrentMatch().setPacket(threeTossupPacketWithMiddleBonus());
            startMatch();
            submit("Napoleon"); // resolve round 1 so we can advance onto the bonus-bearing tossup
            advance(); // move onto the bonus-bearing (2nd) tossup

            assertFalse(isCorrect(submit("nobody")));
            assertEquals(RoundState.COMPLETED, round().getRoundState());
            assertEquals("<b><u>Shakespeare</u></b>", round().getAnswer());
            assertNull(round().getCurrentBonus());
        }
    }

    @Nested
    @DisplayName("Default settings")
    class DefaultSettings {

        @Test
        @DisplayName("All three tossups answered correctly back-to-back completes the whole match")
        void allCorrectSweepsTheMatch() {
            session.getCurrentMatch().setPacket(threeTossupPacketWithMiddleBonus());
            startMatch();

            assertTrue(isCorrect(submit("Napoleon")));
            advance();
            assertTrue(isCorrect(submit("Shakespeare")));
            advance();
            assertTrue(isCorrect(submit("Einstein")));
            SockbowlOutMessage result = advance();

            assertNotNull(result);
            assertEquals(MatchState.COMPLETED, session.getCurrentMatch().getMatchState());
        }

        @Test
        @DisplayName("All three tossups missed still runs the full match to completion")
        void allWrongStillCompletesTheMatch() {
            session.getCurrentMatch().setPacket(threeTossupPacketWithMiddleBonus());
            startMatch();

            assertFalse(isCorrect(submit("nope")));
            advance();
            assertFalse(isCorrect(submit("nope")));
            advance();
            assertFalse(isCorrect(submit("nope")));
            SockbowlOutMessage result = advance();

            assertNotNull(result);
            assertEquals(MatchState.COMPLETED, session.getCurrentMatch().getMatchState());
            assertEquals(3, session.getCurrentMatch().getPreviousRounds().size());
        }
    }
}
