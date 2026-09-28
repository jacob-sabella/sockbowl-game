package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.model.socket.in.game.*;
import com.soulsoftworks.sockbowlgame.client.PacketClient;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.GetGameState;
import com.soulsoftworks.sockbowlgame.model.socket.in.progression.EndMatch;
import com.soulsoftworks.sockbowlgame.model.socket.in.progression.StartMatch;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlMultiOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.game.BonusUpdate;
import com.soulsoftworks.sockbowlgame.model.socket.out.progression.GameSessionUpdate;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.ProcessError;
import com.soulsoftworks.sockbowlgame.model.state.*;
import com.soulsoftworks.sockbowlgame.service.authorization.GameAuthorizationPolicy;
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
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static com.soulsoftworks.sockbowlgame.service.processor.MatchContextUtils.createTeams;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * G-02: in QUIZ_BOWL_CLASSIC only the proctor may read an answer before it has
 * been read out and judged. Drives a whole classic match with bonuses through
 * the real processors and checks every out-message that reaches a non-proctor
 * (a broadcast with no recipients, or one addressed to anyone but the proctor),
 * serialized the way it goes on the wire, plus each non-proctor get-game view:
 * <ul>
 *   <li>a tossup answer only once that tossup has been judged;</li>
 *   <li>a bonus part answer only once that part has been judged (or timed out);</li>
 *   <li>the answers of a bonus that was never played (dead tossup) never,
 *       including in the match-completed and end-match updates and in
 *       previousMatches afterwards (G2-01);</li>
 *   <li>the packet author's Keycloak subject never, to anyone, the proctor
 *       included (G2-02).</li>
 * </ul>
 * get-game goes through the real ConfigurationMessageProcessor.
 */
class ClassicMatchAnswerLeakTest {

    private static final String T1 = "Napoleon";
    private static final String T2 = "Shakespeare";
    private static final String T3 = "Beethoven";
    private static final List<String> B1 = List.of("Aardvark", "Badger", "Cheetah");
    private static final List<String> B2 = List.of("Dolphin", "Eagle", "Falcon"); // never played
    private static final List<String> B3 = List.of("Gorilla", "Heron", "Ibis");

    private static final String PACKET_OWNER_SUB = "kc-packet-author-sub";
    private static final String Q1 = "This French emperor lost at Waterloo.";
    private static final String Q2 = "This English playwright wrote Hamlet.";
    private static final String Q3 = "This composer wrote nine symphonies.";

    private static final ObjectMapper JSON = new ObjectMapper();

    private GameMessageProcessor gameProcessor;
    private ProgressionMessageProcessor progressionProcessor;
    private ConfigurationMessageProcessor configProcessor;
    private GameSession session;
    private Player proctor;
    private Player p1;
    private Player p2;

    /** Answers already read out and judged: fine for anyone to see. */
    private final Set<String> publicAnswers = new HashSet<>();
    private final List<String> allAnswers = new ArrayList<>();
    private int checkedMessages;

    @BeforeEach
    void setup() {
        gameProcessor = new GameMessageProcessor();
        progressionProcessor = new ProgressionMessageProcessor();
        configProcessor = new ConfigurationMessageProcessor(mock(PacketClient.class),
                new GameAuthorizationPolicy(false, null), new com.soulsoftworks.sockbowlgame.support.InMemoryEphemeralPacketBindings());

        proctor = Player.builder().playerId("proctor").name("proctor")
                .playerMode(PlayerMode.PROCTOR).isGameOwner(true).build();
        p1 = Player.builder().playerId("p1").name("one").playerMode(PlayerMode.BUZZER).build();
        p2 = Player.builder().playerId("p2").name("two").playerMode(PlayerMode.BUZZER).build();
        Player watcher = Player.builder().playerId("watcher").name("watcher")
                .playerMode(PlayerMode.SPECTATOR).build();

        List<Team> teams = createTeams(2);
        teams.get(0).addPlayerToTeam(p1);
        teams.get(1).addPlayerToTeam(p2);

        session = GameSession.builder()
                .id("LEAK").joinCode("LEAK")
                .playerList(List.of(proctor, p1, p2, watcher))
                .teamList(teams)
                .currentMatch(new Match())
                .gameSettings(GameSettings.builder()
                        .gameMode(GameMode.QUIZ_BOWL_CLASSIC)
                        .proctorType(ProctorType.ONLINE_PROCTOR)
                        .bonusesEnabled(true)
                        .build())
                .build();
        session.getCurrentMatch().setPacket(packet());
        // A stored session from before G2-02 may still carry the author's subject.
        session.getCurrentMatch().getPacket().setOwnerId(PACKET_OWNER_SUB);

        allAnswers.addAll(List.of(T1, T2, T3));
        allAnswers.addAll(B1);
        allAnswers.addAll(B2);
        allAnswers.addAll(B3);
    }

    private static Packet packet() {
        List<ContainsTossup> tossups = List.of(
                PacketBuilderHelper.createTossup(1, 0, Tossup.builder()
                        .question(Q1).answer("<b><u>" + T1 + "</u></b>").build()),
                PacketBuilderHelper.createTossup(2, 1, Tossup.builder()
                        .question(Q2).answer("<b><u>" + T2 + "</u></b>").build()),
                PacketBuilderHelper.createTossup(3, 2, Tossup.builder()
                        .question(Q3).answer("<b><u>" + T3 + "</u></b>").build()));
        List<ContainsBonus> bonuses = List.of(
                PacketBuilderHelper.createBonus(1, 0, bonus(B1)),
                PacketBuilderHelper.createBonus(2, 1, bonus(B2)),
                PacketBuilderHelper.createBonus(3, 2, bonus(B3)));
        return PacketBuilderHelper.createPacket("PKT", "Leak Packet",
                PacketBuilderHelper.createDifficulty("D1", "Regionals"), tossups, bonuses);
    }

    private static Bonus bonus(List<String> answers) {
        List<HasBonusPart> parts = new ArrayList<>();
        for (int i = 0; i < answers.size(); i++) {
            parts.add(HasBonusPart.builder().order(i)
                    .bonusPart(BonusPart.builder().question("part " + i).answer("<u>" + answers.get(i) + "</u>").build())
                    .build());
        }
        return Bonus.builder().preamble("A three-part bonus.").bonusParts(parts).build();
    }

    /* ------------------------------------------------------------------ */

    private SockbowlOutMessage step(String label, Supplier<SockbowlOutMessage> action) {
        SockbowlOutMessage out = action.get();
        assertNotNull(out, label);
        assertFalse(out instanceof ProcessError, label + " refused: " + out);
        List<SockbowlOutMessage> flat = out instanceof SockbowlMultiOutMessage multi
                ? multi.getSockbowlOutMessages() : List.of(out);
        for (SockbowlOutMessage m : flat) {
            String where = label + " -> " + m.getClass().getSimpleName()
                    + " to " + (m.getRecipients().isEmpty() ? "everyone" : m.getRecipients());
            assertNoOwnerSub(where, m);
            boolean proctorOnly = !m.getRecipients().isEmpty()
                    && m.getRecipients().stream().allMatch(proctor.getPlayerId()::equals);
            if (!proctorOnly) {
                assertNoSecretAnswers(where, m);
            }
        }
        // get-game as each non-proctor at this point, through the real processor.
        for (String playerId : List.of("p1", "p2", "watcher")) {
            SockbowlOutMessage view = getGame(playerId);
            assertNoOwnerSub(label + " -> get-game as " + playerId, view);
            assertNoSecretAnswers(label + " -> get-game as " + playerId, view);
        }
        assertNoOwnerSub(label + " -> get-game as proctor", getGame(proctor.getPlayerId()));
        return out;
    }

    private SockbowlOutMessage getGame(String playerId) {
        SockbowlOutMessage out = configProcessor.processMessage(GetGameState.builder()
                .gameSession(session).originatingPlayerId(playerId).build());
        assertInstanceOf(GameSessionUpdate.class, out);
        assertEquals(List.of(playerId), out.getRecipients());
        return out;
    }

    private void assertNoOwnerSub(String where, Object payload) {
        String json = JSON.writeValueAsString(payload);
        assertFalse(json.contains(PACKET_OWNER_SUB), where + " carries the packet owner's subject: " + json);
    }

    private void assertNoSecretAnswers(String where, Object payload) {
        String json = JSON.writeValueAsString(payload);
        for (String answer : allAnswers) {
            if (!publicAnswers.contains(answer)) {
                assertFalse(json.contains(answer), where + " carries unjudged answer '" + answer + "': " + json);
            }
        }
        checkedMessages++;
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

    private SockbowlOutMessage advance() {
        return gameProcessor.advanceRound(AdvanceRound.builder()
                .gameSession(session).originatingPlayerId(proctor.getPlayerId()).build());
    }

    private SockbowlOutMessage preamble() {
        return gameProcessor.finishedReadingBonusPreamble(FinishedReadingBonusPreamble.builder()
                .gameSession(session).originatingPlayerId(proctor.getPlayerId()).build());
    }

    private SockbowlOutMessage partRead() {
        return gameProcessor.finishedReadingBonusPart(FinishedReadingBonusPart.builder()
                .gameSession(session).originatingPlayerId(proctor.getPlayerId()).build());
    }

    private SockbowlOutMessage partJudged(int idx, boolean correct) {
        return gameProcessor.bonusPartAnswer(BonusPartOutcome.builder()
                .gameSession(session).originatingPlayerId(proctor.getPlayerId())
                .partIndex(idx).correct(correct).build());
    }

    private SockbowlOutMessage partTimeout() {
        return gameProcessor.timeoutBonusPart(TimeoutBonusPart.builder()
                .gameSession(session).originatingPlayerId(proctor.getPlayerId()).build());
    }

    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("No non-proctor message or get-game view carries an unjudged tossup or bonus-part answer across a full classic match")
    void noUnjudgedAnswerReachesANonProctor() {
        step("start", () -> progressionProcessor.startMatch(StartMatch.builder()
                .gameSession(session).originatingPlayerId(proctor.getPlayerId()).build()));

        // Round 1: p1 wins the tossup; the bonus is judged part by part.
        step("r1 read", this::finishedReading);
        step("r1 buzz", () -> buzz(p1));
        publicAnswers.add(T1);
        step("r1 judge", () -> judge(true));
        assertEquals(RoundState.BONUS_READING_PREAMBLE, session.getCurrentRound().getRoundState());
        step("r1 preamble", this::preamble);
        for (int i = 0; i < 3; i++) {
            final int part = i;
            step("r1 part " + i + " read", this::partRead);
            publicAnswers.add(B1.get(i));
            SockbowlOutMessage update = step("r1 part " + i + " judged", () -> partJudged(part, part != 1));
            assertBonusUpdateSplit(update);
        }
        assertEquals(RoundState.COMPLETED, session.getCurrentRound().getRoundState());
        step("r1 advance", this::advance);

        // Round 2: dead tossup. Its bonus is never played, so its answers stay secret.
        step("r2 read", this::finishedReading);
        step("r2 buzz p1", () -> buzz(p1));
        step("r2 judge p1", () -> judge(false));
        step("r2 buzz p2", () -> buzz(p2));
        publicAnswers.add(T2);
        step("r2 judge p2", () -> judge(false));
        assertEquals(RoundState.COMPLETED, session.getCurrentRound().getRoundState());
        assertNull(session.getCurrentRound().getCurrentBonus());
        step("r2 advance", this::advance);

        // Round 3: p2 wins; one bonus part times out.
        step("r3 read", this::finishedReading);
        step("r3 buzz", () -> buzz(p2));
        publicAnswers.add(T3);
        step("r3 judge", () -> judge(true));
        step("r3 preamble", this::preamble);
        step("r3 part 0 read", this::partRead);
        publicAnswers.add(B3.get(0));
        step("r3 part 0 judged", () -> partJudged(0, true));
        step("r3 part 1 read", this::partRead);
        publicAnswers.add(B3.get(1));
        assertBonusUpdateSplit(step("r3 part 1 timeout", this::partTimeout));
        step("r3 part 2 read", this::partRead);
        publicAnswers.add(B3.get(2));
        step("r3 part 2 judged", () -> partJudged(2, false));
        assertEquals(RoundState.COMPLETED, session.getCurrentRound().getRoundState());

        // The never-played bonus is still secret at the last round's end.
        for (String secret : B2) {
            assertFalse(publicAnswers.contains(secret));
        }
        assertTrue(checkedMessages > 50, "checked " + checkedMessages);

        // Last advance completes the match. The proctor gets the proctor view,
        // everyone else the player view: the never-played bonus stays secret (G2-01).
        SockbowlOutMessage completed = step("match completed", this::advance);
        assertEquals(MatchState.COMPLETED, session.getCurrentMatch().getMatchState());
        assertSessionUpdateSplit(completed);

        // end-match files the finished match under previousMatches; neither the
        // end-match updates nor any later get-game carry the unplayed bonus.
        SockbowlOutMessage ended = step("end match", this::endMatch);
        assertSessionUpdateSplit(ended);
        assertEquals(1, session.getPreviousMatches().size());
        assertEquals(MatchState.CONFIG, session.getCurrentMatch().getMatchState());
        for (String secret : B2) {
            assertFalse(publicAnswers.contains(secret));
        }
    }

    @Test
    @DisplayName("end-match at round 1: no unplayed question, answer or owner subject in the updates or a later get-game")
    void endMatchAtRoundOneLeaksNothing() {
        step("start", () -> progressionProcessor.startMatch(StartMatch.builder()
                .gameSession(session).originatingPlayerId(proctor.getPlayerId()).build()));

        // The proctor ends the match while tossup 1 is still being read.
        SockbowlOutMessage ended = step("end match at round 1", this::endMatch);
        assertSessionUpdateSplit(ended);
        assertEquals(1, session.getPreviousMatches().size());
        // The stored finished match still holds the whole packet...
        assertNotNull(session.getPreviousMatches().get(0).getPacket().getTossups());

        // ...but no copy of the update, the proctor's included, carries any of it:
        // nothing of the finished match was ever judged, and the new match has no packet.
        List<SockbowlOutMessage> updates = ((SockbowlMultiOutMessage) ended).getSockbowlOutMessages();
        for (SockbowlOutMessage update : updates) {
            assertNothingOfThePacket("end-match to " + update.getRecipients(), update);
        }
        for (String playerId : List.of("proctor", "p1", "p2", "watcher")) {
            assertNothingOfThePacket("later get-game as " + playerId, getGame(playerId));
        }
        // previousMatches keeps the packet's name for history, nothing more.
        GameSession view = ((GameSessionUpdate) getGame("p1")).getGameSession();
        assertEquals("Leak Packet", view.getPreviousMatches().get(0).getPacket().getName());
        assertNull(view.getPreviousMatches().get(0).getPacket().getTossups());
        assertNull(view.getPreviousMatches().get(0).getPacket().getBonuses());
        assertNull(view.getPreviousMatches().get(0).getPacket().getOwnerId());

        // Reloading the same packet for the next match gives the players nothing in advance.
        session.getCurrentMatch().setPacket(packet());
        for (String playerId : List.of("p1", "p2", "watcher")) {
            assertNothingOfThePacket("get-game after reload as " + playerId, getGame(playerId));
        }
    }

    /** No answer, no tossup question, no bonus text and no owner subject. */
    private void assertNothingOfThePacket(String where, Object payload) {
        String json = JSON.writeValueAsString(payload);
        for (String secret : allAnswers) {
            assertFalse(json.contains(secret), where + " carries answer '" + secret + "': " + json);
        }
        for (String question : List.of(Q1, Q2, Q3, "A three-part bonus.")) {
            assertFalse(json.contains(question), where + " carries question '" + question + "': " + json);
        }
        assertFalse(json.contains(PACKET_OWNER_SUB), where + " carries the packet owner's subject: " + json);
    }

    private SockbowlOutMessage endMatch() {
        return progressionProcessor.endMatch(EndMatch.builder()
                .gameSession(session).originatingPlayerId(proctor.getPlayerId()).build());
    }

    /** A GameSessionUpdate to the proctor alone, and a separate one addressed to everyone else. */
    private void assertSessionUpdateSplit(SockbowlOutMessage out) {
        assertInstanceOf(SockbowlMultiOutMessage.class, out);
        List<SockbowlOutMessage> messages = ((SockbowlMultiOutMessage) out).getSockbowlOutMessages();
        assertTrue(messages.stream().allMatch(GameSessionUpdate.class::isInstance), messages.toString());
        assertTrue(messages.stream().noneMatch(m -> m.getRecipients().isEmpty()),
                "a GameSessionUpdate is broadcast unaddressed: " + messages);
        assertEquals(2, messages.size(), messages.toString());
        assertTrue(messages.stream().anyMatch(m -> m.getRecipients().equals(List.of(proctor.getPlayerId()))));
        assertTrue(messages.stream().anyMatch(m -> m.getRecipients().equals(List.of("p1", "p2", "watcher"))));
    }

    /** The proctor gets the full round; everyone else a separate, addressed copy. */
    private void assertBonusUpdateSplit(SockbowlOutMessage out) {
        assertInstanceOf(SockbowlMultiOutMessage.class, out);
        List<SockbowlOutMessage> messages = ((SockbowlMultiOutMessage) out).getSockbowlOutMessages();
        assertTrue(messages.stream().allMatch(BonusUpdate.class::isInstance), messages.toString());
        assertTrue(messages.stream().noneMatch(m -> m.getRecipients().isEmpty()),
                "a BonusUpdate is broadcast unaddressed: " + messages);
        assertTrue(messages.stream().anyMatch(m -> m.getRecipients().equals(List.of(proctor.getPlayerId()))));
        assertTrue(messages.stream().anyMatch(m -> m.getRecipients().containsAll(List.of("p1", "p2", "watcher"))));
    }
}
