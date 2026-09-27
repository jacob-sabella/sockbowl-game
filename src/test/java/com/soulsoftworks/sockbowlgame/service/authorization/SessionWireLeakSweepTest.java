package com.soulsoftworks.sockbowlgame.service.authorization;

import com.soulsoftworks.sockbowlgame.model.socket.in.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.SetProctor;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.UpdatePlayerTeam;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlMultiOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.ProcessError;
import com.soulsoftworks.sockbowlgame.model.socket.out.progression.GameSessionUpdate;
import com.soulsoftworks.sockbowlgame.model.state.*;
import com.soulsoftworks.sockbowlgame.support.InSessionFixture;
import com.soulsoftworks.sockbowlgame.support.InSessionFixture.Creator;
import com.soulsoftworks.sockbowlgame.support.InSessionFixture.Room;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.soulsoftworks.sockbowlgame.support.StompMappingClassification.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Serialization sweep over every path that puts a {@link GameSession},
 * {@link Match} or {@link Packet} on the wire (G2-01, G2-02, G2-03).
 * <ol>
 *   <li>Type inventory: {@link GameSessionUpdate#getGameSession()} is the only
 *       out-message field that can carry a session, match or packet. A new one
 *       fails here and must be added to the sweep.</li>
 *   <li>Path sweep: every game destination, sent by every player, in every
 *       game mode, from each phase in which nothing has been judged yet
 *       (CONFIG with a packet and no proctor, CONFIG with a proctor, IN_GAME at
 *       round 1, and CONFIG after an end-match at round 1 with the same packet
 *       loaded again, so previousMatches holds a whole packet). Each out-message
 *       is serialized the way it goes on the wire; any copy that can reach
 *       someone other than the proctor must hold no answer, and no copy at all
 *       (the proctor's included) may hold the packet author's subject. get-game,
 *       per-player queues, broadcasts, end-match and the packet reset are all
 *       among the destinations and outputs covered.</li>
 * </ol>
 * The later phases (answers judged, match completed with an unplayed bonus)
 * are covered by {@code ClassicMatchAnswerLeakTest}.
 */
class SessionWireLeakSweepTest {

    private static final String PACKET_OWNER_SUB = "kc-packet-author-sweep";
    /** Every answer in the fixture packet (tossups and bonus parts). */
    private static final List<String> ANSWERS = List.of("Napoleon", "Shakespeare", "alpha", "beta", "gamma");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Set<RoundState> DECIDED = EnumSet.of(RoundState.BONUS_PENDING,
            RoundState.BONUS_READING_PREAMBLE, RoundState.BONUS_READING_PART, RoundState.BONUS_AWAITING_ANSWER,
            RoundState.BONUS_COMPLETED, RoundState.COMPLETED);

    private static final Set<Class<?>> SESSION_TYPES = Set.of(GameSession.class, Match.class, Packet.class);

    /* ------------------------------------------------------------------ */
    /* 1. Type inventory                                                   */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("GameSessionUpdate.gameSession is the only out-message field that carries a session, match or packet")
    void onlyGameSessionUpdateCarriesASession() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(SockbowlOutMessage.class));
        Set<String> carriers = new TreeSet<>();
        int types = 0;
        for (BeanDefinition bd : scanner.findCandidateComponents("com.soulsoftworks.sockbowlgame")) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            types++;
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (carriesSession(f.getGenericType())) {
                        carriers.add(c.getSimpleName() + "." + f.getName());
                    }
                }
            }
        }
        assertTrue(types >= 10, "scanned only " + types + " out-message types");
        assertEquals(Set.of("GameSessionUpdate.gameSession"), carriers,
                "a new out-message field carries a session, match or packet: sanitize it and add it to this sweep");
    }

    private static boolean carriesSession(Type type) {
        if (type instanceof Class<?> c) {
            return SESSION_TYPES.contains(c) || (c.isArray() && carriesSession(c.getComponentType()));
        }
        if (type instanceof ParameterizedType p) {
            if (carriesSession(p.getRawType())) {
                return true;
            }
            for (Type arg : p.getActualTypeArguments()) {
                if (carriesSession(arg)) {
                    return true;
                }
            }
        }
        return false;
    }

    /* ------------------------------------------------------------------ */
    /* 2. Path sweep                                                       */
    /* ------------------------------------------------------------------ */

    private enum Phase { CONFIG_NO_PROCTOR, CONFIG_PROCTOR, IN_GAME, AFTER_END_MATCH }

    @TestFactory
    @DisplayName("No destination, sender, mode or phase puts an answer before a non-proctor, or the packet owner before anyone")
    Stream<DynamicTest> sweep() {
        List<DynamicTest> tests = new ArrayList<>();
        for (GameMode mode : GameMode.values()) {
            for (Creator creator : Creator.values()) {
                for (Phase phase : Phase.values()) {
                    boolean proctored = !GameSettings.builder().gameMode(mode).build().isProctorless();
                    if (!proctored && phase == Phase.CONFIG_PROCTOR) {
                        continue;
                    }
                    tests.add(DynamicTest.dynamicTest(mode + " " + phase + " [" + creator + "]",
                            () -> sweepPhase(mode, creator, phase)));
                }
            }
        }
        return tests.stream();
    }

    private void sweepPhase(GameMode mode, Creator creator, Phase phase) {
        int checked = 0;
        for (String destination : MESSAGE_TYPES.keySet()) {
            List<String> senders = setUp(mode, creator, phase).session().getPlayerList().stream()
                    .map(Player::getPlayerId).toList();
            for (String sender : senders) {
                InSessionFixture fx = new InSessionFixture();
                stubOwnedPacket(fx);
                Room room = setUp(fx, mode, creator, phase);
                SockbowlInMessage message = InSessionFixture.message(destination, room.session(), sender,
                        fieldsFor(destination, room, sender));
                SockbowlOutMessage out = fx.dispatch(message);
                String where = mode + " " + phase + " [" + creator + "] " + destination + " from " + sender;
                checked += checkOut(where, room.session(), out);
            }
        }
        assertTrue(checked > 0, "nothing checked");
    }

    /** Checks every flattened out-message; returns how many were checked. */
    private static int checkOut(String where, GameSession session, SockbowlOutMessage out) {
        assertNotNull(out, where);
        // A single message from round 1 can decide at most tossup 1 (a timeout, or an
        // auto-judged answer); its answer is then public, and nothing else is.
        Set<String> publicAnswers = new HashSet<>();
        Round round = session.getCurrentMatch().getCurrentRound();
        if (round != null && DECIDED.contains(round.getRoundState())) {
            publicAnswers.add("Napoleon");
        }
        List<SockbowlOutMessage> flat = out instanceof SockbowlMultiOutMessage multi
                ? multi.getSockbowlOutMessages() : List.of(out);
        Player proctor = session.getProctor();
        for (SockbowlOutMessage m : flat) {
            String json = JSON.writeValueAsString(m);
            String to = m.getRecipients().isEmpty() ? "everyone" : m.getRecipients().toString();
            assertFalse(json.contains(PACKET_OWNER_SUB),
                    where + " -> " + m.getClass().getSimpleName() + " to " + to + " carries the packet owner: " + json);
            boolean proctorOnly = proctor != null && !m.getRecipients().isEmpty()
                    && m.getRecipients().stream().allMatch(proctor.getPlayerId()::equals);
            if (!proctorOnly) {
                for (String answer : ANSWERS) {
                    if (publicAnswers.contains(answer)) {
                        continue;
                    }
                    assertFalse(json.contains(answer), where + " -> " + m.getClass().getSimpleName() + " to " + to
                            + " carries answer '" + answer + "': " + json);
                }
            }
            if (m instanceof GameSessionUpdate update && !(proctorOnly)) {
                // Structural check as well as the string one: no packet content in any match.
                GameSession copy = update.getGameSession();
                assertNull(copy.getCurrentMatch().getPacket().getTossups(), where + " current tossups to " + to);
                for (Match previous : copy.getPreviousMatches()) {
                    assertNull(previous.getPacket().getTossups(), where + " previous tossups to " + to);
                    assertNull(previous.getPacket().getBonuses(), where + " previous bonuses to " + to);
                }
            }
            if (m instanceof GameSessionUpdate update) {
                // previousMatches is public in every view, the proctor's included.
                for (Match previous : update.getGameSession().getPreviousMatches()) {
                    assertNull(previous.getPacket().getTossups(), where + " previous tossups (proctor view)");
                }
            }
        }
        return flat.size();
    }

    /* ------------------------------------------------------------------ */

    /** The mocked questions service returns the fixture packet with its author's subject. */
    private static void stubOwnedPacket(InSessionFixture fx) {
        when(fx.packetClient.getPacketById(any())).thenAnswer(inv -> Mono.just(ownedPacket()));
    }

    private static Packet ownedPacket() {
        Packet packet = InSessionFixture.packet();
        packet.setOwnerId(PACKET_OWNER_SUB);
        return packet;
    }

    private static Room setUp(GameMode mode, Creator creator, Phase phase) {
        InSessionFixture fx = new InSessionFixture();
        stubOwnedPacket(fx);
        return setUp(fx, mode, creator, phase);
    }

    /** A room of {@code mode} in {@code phase}, with the owned packet loaded (as a stored session may hold it). */
    private static Room setUp(InSessionFixture fx, GameMode mode, Creator creator, Phase phase) {
        Room room = fx.room(creator, mode);
        GameSession session = room.session();
        boolean proctored = !session.getGameSettings().isProctorless();
        session.getCurrentMatch().setPacket(ownedPacket());
        if (phase == Phase.CONFIG_NO_PROCTOR) {
            return room;
        }
        if (proctored) {
            // Keep a player on both teams: move a non-owner next to the teammate,
            // then seat the teammate as proctor.
            String mover = room.nonOwners().get(room.nonOwners().size() - 1);
            session.getTeamByPlayerId(mover).removePlayerFromTeam(mover);
            session.getTeamByPlayerId(room.teammate()).addPlayerToTeam(room.player(mover));
            InSessionFixture.makeProctor(session, room.teammate());
        }
        if (phase == Phase.CONFIG_PROCTOR) {
            return room;
        }
        String runner = proctored ? room.teammate() : room.owner();
        SockbowlOutMessage started = fx.dispatch(InSessionFixture.message(START_MATCH, session, runner, null));
        assertFalse(started instanceof ProcessError, "fixture: start refused: " + started);
        if (phase == Phase.IN_GAME) {
            return room;
        }
        SockbowlOutMessage ended = fx.dispatch(InSessionFixture.message(END_MATCH, session, runner, null));
        assertFalse(ended instanceof ProcessError, "fixture: end refused: " + ended);
        assertEquals(1, session.getPreviousMatches().size());
        // The same packet is loaded again for the next match.
        session.getCurrentMatch().setPacket(ownedPacket());
        return room;
    }

    /** Well-formed bodies: a sender moves themselves to the other team, or claims the seat themselves. */
    private static Consumer<SockbowlInMessage> fieldsFor(String destination, Room room, String sender) {
        if (UPDATE_PLAYER_TEAM.equals(destination)) {
            return m -> {
                Team current = room.session().getTeamByPlayerId(sender);
                Team first = room.session().getTeamList().get(0);
                Team target = current != null && first.getTeamId().equals(current.getTeamId())
                        && room.session().getTeamList().size() > 1
                        ? room.session().getTeamList().get(1) : first;
                ((UpdatePlayerTeam) m).setTargetPlayer(sender);
                ((UpdatePlayerTeam) m).setTargetTeam(target.getTeamId());
            };
        }
        if (SET_PROCTOR.equals(destination)) {
            return m -> ((SetProctor) m).setTargetPlayer(sender);
        }
        return null;
    }
}
