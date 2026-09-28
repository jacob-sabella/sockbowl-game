package com.soulsoftworks.sockbowlgame.service.authorization;

import com.google.gson.Gson;
import com.soulsoftworks.sockbowlgame.config.WebSocketLimitsProperties;
import com.soulsoftworks.sockbowlgame.controller.websocket.StompExceptionAdvice;
import com.soulsoftworks.sockbowlgame.ratelimit.Decision;
import com.soulsoftworks.sockbowlgame.ratelimit.QuotaExceededException;
import com.soulsoftworks.sockbowlgame.security.stomp.StompErrorCode;
import com.soulsoftworks.sockbowlgame.security.stomp.StompPrincipal;
import com.soulsoftworks.sockbowlgame.security.stomp.StompRejectedException;
import com.soulsoftworks.sockbowlgame.websocket.RateLimitedNotifier;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import java.time.Clock;
import java.time.Instant;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.SetMatchPacket;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.SetProctor;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.UpdateGameSettings;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlMultiOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.config.MatchPacketUpdate;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.ProcessError;
import com.soulsoftworks.sockbowlgame.model.socket.out.progression.GameSessionUpdate;
import com.soulsoftworks.sockbowlgame.model.state.GameMode;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.state.GameSettings;
import com.soulsoftworks.sockbowlgame.model.state.Player;
import com.soulsoftworks.sockbowlgame.model.state.PlayerMode;
import com.soulsoftworks.sockbowlgame.model.state.TimerSettings;
import com.soulsoftworks.sockbowlgame.service.processor.ConfigurationMessageProcessor;
import com.soulsoftworks.sockbowlgame.support.InSessionFixture;
import com.soulsoftworks.sockbowlgame.support.InSessionFixture.Creator;
import com.soulsoftworks.sockbowlgame.support.InSessionFixture.Room;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.soulsoftworks.sockbowlgame.support.StompMappingClassification.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R3-G-01: a non-proctor must never learn the loaded packet's id, and an
 * EPHEMERAL packet is bound to the one game that first loaded it. Before the
 * fix every player got the id in the MatchPacketUpdate broadcast and in
 * get-game, and could load the packet as proctor of a second, self-created
 * game and read every answer there (the cross-game answer oracle).
 */
class PacketIdExposureAuthTest {

    private static final String[] FIXTURE_ANSWERS = {"Napoleon", "Shakespeare", "alpha", "beta", "gamma"};
    private static final Gson GSON = new Gson();
    private static final ObjectMapper JACKSON = new ObjectMapper();

    static List<SockbowlOutMessage> flat(SockbowlOutMessage out) {
        List<SockbowlOutMessage> all = new ArrayList<>();
        if (out instanceof SockbowlMultiOutMessage multi) {
            for (SockbowlOutMessage m : multi.getSockbowlOutMessages()) {
                all.addAll(flat(m));
            }
        } else if (out != null) {
            all.add(out);
        }
        return all;
    }

    /** The frames {@code playerId} receives out of {@code out} (a broadcast reaches everyone). */
    static List<SockbowlOutMessage> framesFor(SockbowlOutMessage out, String playerId) {
        return flat(out).stream()
                .filter(m -> m.getRecipients().isEmpty() || m.getRecipients().contains(playerId))
                .toList();
    }

    /** Both wire encodings: Jackson (STOMP) and Gson (REST/Redis). */
    static String wire(Object frame) {
        try {
            return JACKSON.writeValueAsString(frame) + "\n" + GSON.toJson(frame);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String getGameJson(InSessionFixture fx, Room room, String playerId) {
        List<SockbowlOutMessage> frames = flat(fx.dispatch(InSessionFixture.message(GET_GAME, room.session(), playerId, null)));
        assertEquals(1, frames.size());
        return wire(((GameSessionUpdate) frames.get(0)).getGameSession());
    }

    static SockbowlOutMessage claim(InSessionFixture fx, Room room, String sender) {
        return fx.dispatch(InSessionFixture.message(SET_PROCTOR, room.session(), sender,
                m -> ((SetProctor) m).setTargetPlayer(sender)));
    }

    static SockbowlOutMessage load(InSessionFixture fx, Room room, String sender, String packetId) {
        return fx.dispatch(InSessionFixture.message(SET_MATCH_PACKET, room.session(), sender,
                m -> ((SetMatchPacket) m).setPacketId(packetId)));
    }

    static void givenVisibility(InSessionFixture fx, PacketVisibility visibility) {
        when(fx.packetClient.getPacketById(any())).thenAnswer(inv -> {
            Packet p = InSessionFixture.packet();
            p.setVisibility(visibility);
            return Mono.just(p);
        });
    }

    static void assertNoAnswers(String json) {
        for (String answer : FIXTURE_ANSWERS) {
            assertFalse(json.contains(answer), "answer '" + answer + "' leaked: " + json);
        }
    }

    /* ------------------------------------------------------------------ */
    /* The cross-game oracle (verifier probe crossGameOracle_...)         */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("R3-G-01: an EPHEMERAL packet loaded in one game is PACKET_NOT_AVAILABLE in a second game")
    void crossGameOracleEphemeralPacketIsRefusedInASecondGame() {
        InSessionFixture fx = new InSessionFixture();
        givenVisibility(fx, PacketVisibility.EPHEMERAL);

        // Game 1: AUTO_PROCTOR (no proctor at all), the owner loads the generated packet.
        Room g1 = fx.room(Creator.GUEST, GameMode.AUTO_PROCTOR);
        assertFalse(load(fx, g1, g1.owner(), InSessionFixture.PACKET_ID) instanceof ProcessError);
        String player = g1.nonOwners().get(0);
        String view = getGameJson(fx, g1, player);
        assertFalse(view.contains(InSessionFixture.PACKET_ID), "player view carries the packet id: " + view);
        assertNoAnswers(view);

        // Game 2: a guest creates a classic game, takes its proctor seat and
        // loads the same id (learned any other way).
        Room g2 = fx.room(Creator.GUEST, GameMode.QUIZ_BOWL_CLASSIC);
        g2.session().setId("SESSION-SECOND-GAME");
        String attacker = g2.owner();
        assertFalse(claim(fx, g2, attacker) instanceof ProcessError);
        SockbowlOutMessage refused = load(fx, g2, attacker, InSessionFixture.PACKET_ID);

        ProcessError error = assertInstanceOf(ProcessError.class, refused);
        assertEquals(ConfigurationMessageProcessor.PACKET_NOT_AVAILABLE, error.getCode());
        assertEquals(List.of(attacker), error.getRecipients());
        assertNull(g2.session().loadedPacketId(), "the second game must not hold the packet");
        assertNoAnswers(getGameJson(fx, g2, attacker));
        assertEquals(g1.session().getId(), fx.ephemeralBindings.boundGame(InSessionFixture.PACKET_ID));
    }

    @Test
    @DisplayName("R3-G-01: the game that holds an EPHEMERAL packet can load it again")
    void ephemeralPacketReloadsInItsOwnGame() {
        InSessionFixture fx = new InSessionFixture();
        givenVisibility(fx, PacketVisibility.EPHEMERAL);
        Room room = fx.room(Creator.GUEST, GameMode.QUIZ_BOWL_CLASSIC);
        String proctor = room.teammate();
        assertFalse(claim(fx, room, proctor) instanceof ProcessError);

        assertFalse(load(fx, room, proctor, InSessionFixture.PACKET_ID) instanceof ProcessError);
        assertFalse(load(fx, room, proctor, InSessionFixture.PACKET_ID) instanceof ProcessError);
        assertEquals(InSessionFixture.PACKET_ID, room.session().loadedPacketId());
    }

    @ParameterizedTest
    @EnumSource(value = PacketVisibility.class, names = {"PUBLISHED"})
    @DisplayName("D20: a PUBLISHED packet stays loadable by the proctor of any game")
    void publishedPacketLoadsInEveryGame(PacketVisibility visibility) {
        InSessionFixture fx = new InSessionFixture();
        givenVisibility(fx, visibility);
        Room g1 = fx.room(Creator.GUEST, GameMode.AUTO_PROCTOR);
        assertFalse(load(fx, g1, g1.owner(), InSessionFixture.PACKET_ID) instanceof ProcessError);
        Room g2 = fx.room(Creator.GUEST, GameMode.QUIZ_BOWL_CLASSIC);
        g2.session().setId("SESSION-SECOND-GAME");
        assertFalse(claim(fx, g2, g2.owner()) instanceof ProcessError);

        assertFalse(load(fx, g2, g2.owner(), InSessionFixture.PACKET_ID) instanceof ProcessError);
        assertNull(fx.ephemeralBindings.boundGame(InSessionFixture.PACKET_ID), "only EPHEMERAL packets are bound");
    }

    @Test
    @DisplayName("Auth off: EPHEMERAL packets are not bound (local dev, like every other packet check)")
    void authOffDoesNotBindEphemeralPackets() {
        InSessionFixture fx = new InSessionFixture(false);
        givenVisibility(fx, PacketVisibility.EPHEMERAL);
        Room g1 = fx.room(Creator.GUEST, GameMode.AUTO_PROCTOR);
        assertFalse(load(fx, g1, g1.owner(), InSessionFixture.PACKET_ID) instanceof ProcessError);
        Room g2 = fx.room(Creator.GUEST, GameMode.AUTO_PROCTOR);
        g2.session().setId("SESSION-SECOND-GAME");

        assertFalse(load(fx, g2, g2.owner(), InSessionFixture.PACKET_ID) instanceof ProcessError);
    }

    @Test
    @DisplayName("R3-G-01: a binding store failure refuses the load (fail closed)")
    void bindingStoreFailureRefusesTheLoad() {
        InSessionFixture fx = new InSessionFixture();
        givenVisibility(fx, PacketVisibility.EPHEMERAL);
        Room room = fx.room(Creator.GUEST, GameMode.AUTO_PROCTOR);
        ConfigurationMessageProcessor processor = new ConfigurationMessageProcessor(fx.packetClient, fx.policy,
                (packetId, gameSessionId) -> {
                    throw new IllegalStateException("redis down");
                });

        SockbowlOutMessage out = processor.processMessage(InSessionFixture.message(SET_MATCH_PACKET, room.session(),
                room.owner(), null));

        ProcessError error = assertInstanceOf(ProcessError.class, out);
        assertEquals(ConfigurationMessageProcessor.PACKET_SERVICE_UNAVAILABLE, error.getCode());
        assertNull(room.session().loadedPacketId());
    }

    /* ------------------------------------------------------------------ */
    /* The id reaches only the loader                                     */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("R3-G-01: the proctor gets the full MatchPacketUpdate, every other player the name and counts without the id")
    void matchPacketUpdateIsPerRecipientInAProctoredGame() {
        InSessionFixture fx = new InSessionFixture();
        Room room = fx.room(Creator.AUTHENTICATED, GameMode.QUIZ_BOWL_CLASSIC);
        String proctor = room.teammate();
        assertFalse(claim(fx, room, proctor) instanceof ProcessError);

        SockbowlOutMessage out = load(fx, room, proctor, InSessionFixture.PACKET_ID);

        List<MatchPacketUpdate> updates = flat(out).stream()
                .map(m -> assertInstanceOf(MatchPacketUpdate.class, m)).toList();
        assertEquals(2, updates.size());
        for (MatchPacketUpdate u : updates) {
            assertFalse(u.getRecipients().isEmpty(), "no MatchPacketUpdate may be a broadcast: " + u);
            assertEquals("Fixture Packet", u.getPacketName());
            assertEquals(2, u.getTossupCount());
            assertEquals(2, u.getBonusCount());
        }
        MatchPacketUpdate full = updates.stream().filter(u -> u.getRecipients().contains(proctor)).findFirst().orElseThrow();
        assertEquals(List.of(proctor), full.getRecipients());
        assertEquals(InSessionFixture.PACKET_ID, full.getPacketId());

        MatchPacketUpdate publicUpdate = updates.stream().filter(u -> u != full).findFirst().orElseThrow();
        assertNull(publicUpdate.getPacketId());
        Set<String> everyoneElse = new java.util.HashSet<>();
        room.session().getPlayerList().forEach(p -> everyoneElse.add(p.getPlayerId()));
        everyoneElse.remove(proctor);
        assertEquals(everyoneElse, Set.copyOf(publicUpdate.getRecipients()));
        assertFalse(wire(publicUpdate).contains(InSessionFixture.PACKET_ID), wire(publicUpdate));
    }

    @ParameterizedTest
    @EnumSource(value = GameMode.class, names = {"AUTO_PROCTOR", "FREE_FOR_ALL"})
    @DisplayName("R3-G-01: in a proctorless mode the owner gets the full MatchPacketUpdate, nobody else the id")
    void matchPacketUpdateIsPerRecipientInAProctorlessGame(GameMode mode) {
        InSessionFixture fx = new InSessionFixture();
        Room room = fx.room(Creator.GUEST, mode);

        SockbowlOutMessage out = load(fx, room, room.owner(), InSessionFixture.PACKET_ID);

        for (Player p : room.session().getPlayerList()) {
            List<SockbowlOutMessage> frames = framesFor(out, p.getPlayerId());
            assertEquals(1, frames.size(), "one MatchPacketUpdate each");
            MatchPacketUpdate u = assertInstanceOf(MatchPacketUpdate.class, frames.get(0));
            assertEquals(2, u.getBonusCount());
            if (p.getPlayerId().equals(room.owner())) {
                assertEquals(InSessionFixture.PACKET_ID, u.getPacketId());
            } else {
                assertNull(u.getPacketId());
                assertEquals("Fixture Packet", u.getPacketName());
            }
        }
    }

    @Test
    @DisplayName("A single-player session: the owner is the only recipient of the full update")
    void loaderAloneGetsOnlyTheFullUpdate() {
        InSessionFixture fx = new InSessionFixture();
        Room room = fx.room(Creator.GUEST, GameMode.AUTO_PROCTOR);
        GameSession session = room.session();
        session.getPlayerList().removeIf(p -> !p.getPlayerId().equals(room.owner()));

        SockbowlOutMessage out = load(fx, room, room.owner(), InSessionFixture.PACKET_ID);

        MatchPacketUpdate u = assertInstanceOf(MatchPacketUpdate.class, out);
        assertEquals(List.of(room.owner()), u.getRecipients());
        assertEquals(InSessionFixture.PACKET_ID, u.getPacketId());
    }

    @Test
    @DisplayName("R3-G-01: no frame a non-proctor receives, from loading to a finished match, carries the packet id")
    void noNonProctorFrameOrGetGameCarriesThePacketId() {
        InSessionFixture fx = new InSessionFixture();
        Room room = fx.room(Creator.AUTHENTICATED, GameMode.QUIZ_BOWL_CLASSIC);
        String proctor = room.teammate();
        // The teammate becomes proctor; give team 2 a player so the match can start.
        String secondTeamPlayer = room.nonOwners().get(0);
        List<SockbowlOutMessage> outs = new ArrayList<>();
        outs.add(claim(fx, room, proctor));
        outs.add(fx.dispatch(InSessionFixture.message(UPDATE_PLAYER_TEAM, room.session(), room.owner(), m -> {
            ((com.soulsoftworks.sockbowlgame.model.socket.in.config.UpdatePlayerTeam) m).setTargetPlayer(secondTeamPlayer);
            ((com.soulsoftworks.sockbowlgame.model.socket.in.config.UpdatePlayerTeam) m)
                    .setTargetTeam(room.session().getTeamList().get(1).getTeamId());
        })));
        outs.add(load(fx, room, proctor, InSessionFixture.PACKET_ID));
        outs.add(fx.dispatch(InSessionFixture.message(START_MATCH, room.session(), proctor, null)));
        assertFalse(outs.get(outs.size() - 1) instanceof ProcessError, "start refused: " + outs.get(outs.size() - 1));
        outs.add(fx.dispatch(InSessionFixture.message(END_MATCH, room.session(), proctor, null)));
        for (SockbowlOutMessage out : outs) {
            assertFalse(out instanceof ProcessError, "step refused: " + out);
        }
        assertFalse(room.session().getPreviousMatches().isEmpty(), "the match is now a previous match");

        for (Player p : room.session().getPlayerList()) {
            if (p.getPlayerId().equals(proctor)) {
                continue;
            }
            for (SockbowlOutMessage out : outs) {
                for (SockbowlOutMessage frame : framesFor(out, p.getPlayerId())) {
                    assertFalse(wire(frame).contains(InSessionFixture.PACKET_ID),
                            p.getName() + " received the packet id in " + wire(frame));
                }
            }
            assertFalse(getGameJson(fx, room, p.getPlayerId()).contains(InSessionFixture.PACKET_ID),
                    p.getName() + "'s get-game carries the packet id");
        }
        // The proctor keeps it in their own view.
        assertTrue(getGameJson(fx, room, proctor).contains(InSessionFixture.PACKET_ID));
    }

    @Test
    @DisplayName("R3-G-01: a settings change broadcast carries no packet id to non-proctors")
    void settingsUpdateCarriesNoPacketIdToNonProctors() {
        InSessionFixture fx = new InSessionFixture();
        Room room = fx.room(Creator.GUEST, GameMode.QUIZ_BOWL_CLASSIC);
        String proctor = room.teammate();
        assertFalse(claim(fx, room, proctor) instanceof ProcessError);
        assertFalse(load(fx, room, proctor, InSessionFixture.PACKET_ID) instanceof ProcessError);

        SockbowlOutMessage out = fx.dispatch(InSessionFixture.message(UPDATE_GAME_SETTINGS, room.session(), proctor,
                m -> ((UpdateGameSettings) m).setGameSettings(GameSettings.builder()
                        .gameMode(GameMode.QUIZ_BOWL_CLASSIC).bonusesEnabled(false)
                        .timerSettings(new TimerSettings()).build())));
        assertFalse(out instanceof ProcessError, "" + out);
        assertEquals(InSessionFixture.PACKET_ID, room.session().loadedPacketId(), "same mode keeps the packet");

        for (Player p : room.session().getPlayerList()) {
            for (SockbowlOutMessage frame : framesFor(out, p.getPlayerId())) {
                boolean carries = wire(frame).contains(InSessionFixture.PACKET_ID);
                assertEquals(p.getPlayerMode() == PlayerMode.PROCTOR, carries, p.getName() + ": " + wire(frame));
            }
        }
    }

    @Test
    @DisplayName("No view carries the proctorsByPacketId bookkeeping")
    void noViewCarriesProctorBookkeeping() {
        InSessionFixture fx = new InSessionFixture();
        Room room = fx.room(Creator.GUEST, GameMode.QUIZ_BOWL_CLASSIC);
        String proctor = room.teammate();
        assertFalse(claim(fx, room, proctor) instanceof ProcessError);
        assertFalse(load(fx, room, proctor, InSessionFixture.PACKET_ID) instanceof ProcessError);
        assertTrue(room.session().hasProctoredPacket(InSessionFixture.PACKET_ID, proctor));

        for (Player p : room.session().getPlayerList()) {
            assertFalse(getGameJson(fx, room, p.getPlayerId()).contains("\"proctorsByPacketId\":{"));
        }
    }

    /* ------------------------------------------------------------------ */
    /* M4 message types (merge-forward of M2 into goal/m4-limits)          */
    /* ------------------------------------------------------------------ */

    /**
     * M4's own frames to a player: the RATE_LIMITED notice for a dropped frame
     * (it echoes the dropped destination), and the STOMP advice's error frames
     * (QUOTA_EXCEEDED, RATE_LIMITED/IP_BANNED rejections, and the generic
     * INVALID_REQUEST for an IllegalArgumentException whose message names the
     * packet). None of them may carry the loaded packet's id or an answer to a
     * non-proctor, even when the dropped or failed frame was a set-match-packet.
     */
    @Test
    @DisplayName("M4: RATE_LIMITED, QUOTA_EXCEEDED, ban and invalid-request frames carry no packet id to a non-proctor")
    void m4ErrorFramesCarryNoPacketId() {
        InSessionFixture fx = new InSessionFixture();
        Room room = fx.room(Creator.GUEST, GameMode.QUIZ_BOWL_CLASSIC);
        String proctor = room.teammate();
        assertFalse(claim(fx, room, proctor) instanceof ProcessError);
        assertFalse(load(fx, room, proctor, InSessionFixture.PACKET_ID) instanceof ProcessError);
        assertEquals(InSessionFixture.PACKET_ID, room.session().loadedPacketId());
        String player = room.nonOwners().stream().filter(id -> !id.equals(proctor)).findFirst().orElseThrow();
        StompPrincipal principal = StompPrincipal.guest(room.session().getId(), player);

        List<Object> frames = new ArrayList<>();

        // The RATE_LIMITED notice for a dropped set-match-packet frame.
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SimpMessagingTemplate> templates = mock(ObjectProvider.class);
        when(templates.getIfAvailable()).thenReturn(template);
        RateLimitedNotifier notifier = new RateLimitedNotifier(templates, new WebSocketLimitsProperties(),
                Clock.systemUTC());
        assertTrue(notifier.notifyDropped(principal, "conn-" + player, "stomp-game",
                Decision.rejected(1_000_000_000L, 10), SET_MATCH_PACKET));
        ArgumentCaptor<Object> notice = ArgumentCaptor.forClass(Object.class);
        verify(template).convertAndSendToUser(eq(principal.getName()), eq(RateLimitedNotifier.ERRORS_DESTINATION),
                notice.capture());
        frames.add(notice.getValue());

        // The STOMP advice's M4 frames.
        @SuppressWarnings("unchecked")
        ObjectProvider<Clock> clocks = mock(ObjectProvider.class);
        when(clocks.getIfAvailable(any())).thenReturn(Clock.systemUTC());
        StompExceptionAdvice advice = new StompExceptionAdvice(clocks);
        frames.add(advice.handleQuotaExceeded(new QuotaExceededException("hosted-sessions", 1, 1, null)));
        frames.add(advice.handleQuotaExceeded(new QuotaExceededException("packets-daily", 5, 5,
                Instant.now().plusSeconds(3600))));
        frames.add(advice.handleRejected(new StompRejectedException(StompErrorCode.RATE_LIMITED,
                "Too many messages", 2, "stomp-game")));
        frames.add(advice.handleRejected(new StompRejectedException(StompErrorCode.IP_BANNED, "Banned")));
        frames.add(advice.handleIllegalArgument(new IllegalArgumentException(
                "cannot load packet " + InSessionFixture.PACKET_ID + " (Napoleon)")));

        assertEquals(6, frames.size());
        for (Object frame : frames) {
            String json = wire(frame);
            assertFalse(json.contains(InSessionFixture.PACKET_ID), "M4 frame carries the packet id: " + json);
            assertNoAnswers(json);
        }
        // The notice still names the dropped destination (the client's own frame).
        assertTrue(wire(frames.get(0)).contains(SET_MATCH_PACKET));
    }
}
