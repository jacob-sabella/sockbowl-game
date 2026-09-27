package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.client.PacketNotFoundException;
import com.soulsoftworks.sockbowlgame.client.QuestionsUnavailableException;
import com.soulsoftworks.sockbowlgame.client.QuestionsUnavailableException.Reason;
import com.soulsoftworks.sockbowlgame.model.socket.in.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.config.MatchPacketUpdate;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.ProcessError;
import com.soulsoftworks.sockbowlgame.model.state.GameMode;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.quota.QuotaProperties;
import com.soulsoftworks.sockbowlgame.service.MessageService;
import com.soulsoftworks.sockbowlgame.service.SessionService;
import com.soulsoftworks.sockbowlgame.usage.HostedSessionQuota;
import com.soulsoftworks.sockbowlgame.support.InSessionFixture;
import com.soulsoftworks.sockbowlgame.support.InSessionFixture.Creator;
import com.soulsoftworks.sockbowlgame.support.InSessionFixture.Room;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.Set;

import static com.soulsoftworks.sockbowlgame.support.InSessionFixture.HOST_SUB;
import static com.soulsoftworks.sockbowlgame.support.InSessionFixture.OTHER_SUB;
import static com.soulsoftworks.sockbowlgame.support.StompMappingClassification.SET_MATCH_PACKET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The SetMatchPacket visibility check (plan m2-auth WP-G4, section 2.4 game
 * side; D2 and D15) and its failure handling (section 2.7).
 *
 * <p>The game fetches every packet with its service token, which may read any
 * packet in full, so this check is what stops a player from loading someone
 * else's DRAFT and reading its answers as proctor. PUBLISHED, legacy (null) and
 * EPHEMERAL packets are open to any setter; a DRAFT only to its owner or a
 * {@code packet:manage-any} holder, judged by the sender identity stamped from
 * the STOMP principal. With auth off the check is skipped.
 */
class SetMatchPacketVisibilityTest {

    private static final String STRANGER_SUB = "kc-stranger";
    private static final Set<String> PLAYER_AUTHORITIES = Set.of("game:host", "game:join");

    private final InSessionFixture fx = new InSessionFixture();

    private static Packet packet(PacketVisibility visibility, String ownerId) {
        Packet packet = InSessionFixture.packet();
        packet.setVisibility(visibility);
        packet.setOwnerId(ownerId);
        return packet;
    }

    private static PacketVisibility ephemeralOrSkip() {
        PacketVisibility ephemeral = Arrays.stream(PacketVisibility.values())
                .filter(v -> v.name().equals("EPHEMERAL")).findFirst().orElse(null);
        assumeTrue(ephemeral != null, "models jar without PacketVisibility.EPHEMERAL (WP-Q4, models 1.0.2)");
        return ephemeral;
    }

    private void questionsReturns(Packet packet) {
        when(fx.packetClient.getPacketById(anyString())).thenReturn(Mono.just(packet));
    }

    /** A guest-created, human-proctored room whose proctor is a guest. Returns the proctor's id. */
    private static String guestProctor(Room room) {
        InSessionFixture.makeProctor(room.session(), room.teammate());
        return room.teammate();
    }

    private static SockbowlInMessage setPacket(Room room, String sender, String senderSub, Set<String> authorities) {
        return InSessionFixture.message(SET_MATCH_PACKET, room.session(), sender, m -> {
            m.setOriginatingKeycloakId(senderSub);
            m.setOriginatingAuthorities(authorities);
        });
    }

    private static void assertLoaded(SockbowlOutMessage out, GameSession session) {
        assertThat(out).isInstanceOf(MatchPacketUpdate.class);
        assertThat(((MatchPacketUpdate) out).getPacketId()).isEqualTo(InSessionFixture.PACKET_ID);
        assertThat(session.getCurrentMatch().getPacket()).isNotNull();
    }

    /** Dispatch a SetMatchPacket that must be rejected with {@code code}, leaving the match's packet untouched. */
    private static void assertRejected(InSessionFixture fx, SockbowlInMessage message, String code,
                                       GameSession session, String sender) {
        Packet before = session.getCurrentMatch().getPacket();
        SockbowlOutMessage out = fx.dispatch(message);
        assertThat(out).isInstanceOf(ProcessError.class);
        ProcessError error = (ProcessError) out;
        assertThat(error.getCode()).isEqualTo(code);
        assertThat(error.getRecipients()).containsExactly(sender);
        assertThat(session.getCurrentMatch().getPacket()).as("session state unchanged").isSameAs(before);
        assertThat(before.getId()).as("no packet was loaded").isNull();
    }

    /* ---------------------------- open packets ---------------------------- */

    @Test
    void publishedPacketIsOkForAGuestProctor() {
        Room room = fx.room(Creator.GUEST, GameMode.QUIZ_BOWL_CLASSIC);
        String proctor = guestProctor(room);
        questionsReturns(packet(PacketVisibility.PUBLISHED, STRANGER_SUB));

        assertLoaded(fx.dispatch(setPacket(room, proctor, null, Set.of())), room.session());
    }

    @Test
    void legacyPacketWithoutVisibilityIsOkForAGuestProctor() {
        Room room = fx.room(Creator.GUEST, GameMode.QUIZ_BOWL_CLASSIC);
        String proctor = guestProctor(room);
        questionsReturns(packet(null, null));

        assertLoaded(fx.dispatch(setPacket(room, proctor, null, Set.of())), room.session());
    }

    @Test
    void ephemeralPacketIsOkForAGuestProctor() {
        PacketVisibility ephemeral = ephemeralOrSkip();
        Room room = fx.room(Creator.GUEST, GameMode.QUIZ_BOWL_CLASSIC);
        String proctor = guestProctor(room);
        questionsReturns(packet(ephemeral, null));

        assertLoaded(fx.dispatch(setPacket(room, proctor, null, Set.of())), room.session());
    }

    @Test
    void ephemeralPacketIsOkForAGuestOwnerInAProctorlessRoom() {
        PacketVisibility ephemeral = ephemeralOrSkip();
        Room room = fx.room(Creator.GUEST, GameMode.AUTO_PROCTOR);
        questionsReturns(packet(ephemeral, null));

        assertLoaded(fx.dispatch(setPacket(room, room.owner(), null, Set.of())), room.session());
    }

    /* ------------------------------- drafts ------------------------------- */

    @Test
    void anotherUsersDraftIsNotAvailableToAGuestProctor() {
        Room room = fx.room(Creator.GUEST, GameMode.QUIZ_BOWL_CLASSIC);
        String proctor = guestProctor(room);
        questionsReturns(packet(PacketVisibility.DRAFT, STRANGER_SUB));

        assertRejected(fx, setPacket(room, proctor, null, Set.of()),
                ConfigurationMessageProcessor.PACKET_NOT_AVAILABLE, room.session(), proctor);
    }

    @Test
    void anotherUsersDraftIsNotAvailableToASignedInNonOwner() {
        Room room = fx.room(Creator.AUTHENTICATED, GameMode.QUIZ_BOWL_CLASSIC);
        String other = room.nonOwners().get(1); // joined with OTHER_SUB
        InSessionFixture.makeProctor(room.session(), other);
        questionsReturns(packet(PacketVisibility.DRAFT, HOST_SUB));

        assertRejected(fx, setPacket(room, other, OTHER_SUB, PLAYER_AUTHORITIES),
                ConfigurationMessageProcessor.PACKET_NOT_AVAILABLE, room.session(), other);
    }

    @Test
    void ownerlessDraftIsNotAvailableToAGuest() {
        // A null owner must never match a guest's null subject.
        Room room = fx.room(Creator.GUEST, GameMode.AUTO_PROCTOR);
        questionsReturns(packet(PacketVisibility.DRAFT, null));

        assertRejected(fx, setPacket(room, room.owner(), null, Set.of()),
                ConfigurationMessageProcessor.PACKET_NOT_AVAILABLE, room.session(), room.owner());
    }

    @Test
    void yourOwnDraftIsOk() {
        Room room = fx.room(Creator.AUTHENTICATED, GameMode.AUTO_PROCTOR);
        questionsReturns(packet(PacketVisibility.DRAFT, HOST_SUB));

        assertLoaded(fx.dispatch(setPacket(room, room.owner(), HOST_SUB, PLAYER_AUTHORITIES)), room.session());
    }

    @Test
    void manageAnyMayLoadSomeoneElsesDraft() {
        Room room = fx.room(Creator.AUTHENTICATED, GameMode.AUTO_PROCTOR);
        questionsReturns(packet(PacketVisibility.DRAFT, STRANGER_SUB));

        assertLoaded(fx.dispatch(setPacket(room, room.owner(), HOST_SUB, Set.of("packet:manage-any"))),
                room.session());
    }

    @Test
    void theOwnerCheckUsesTheStampedSenderNotThePlayersStoredIdentity() {
        // The host's player record says kc-host, but this message was stamped with
        // no subject (a guest connection): only the stamped identity counts.
        Room room = fx.room(Creator.AUTHENTICATED, GameMode.AUTO_PROCTOR);
        questionsReturns(packet(PacketVisibility.DRAFT, HOST_SUB));

        assertRejected(fx, setPacket(room, room.owner(), null, Set.of()),
                ConfigurationMessageProcessor.PACKET_NOT_AVAILABLE, room.session(), room.owner());
    }

    @Test
    void authDisabledLetsAGuestSetADraft() {
        InSessionFixture authOff = new InSessionFixture(false);
        Room room = authOff.room(Creator.GUEST, GameMode.QUIZ_BOWL_CLASSIC);
        String proctor = guestProctor(room);
        when(authOff.packetClient.getPacketById(anyString()))
                .thenReturn(Mono.just(packet(PacketVisibility.DRAFT, STRANGER_SUB)));

        assertLoaded(authOff.dispatch(setPacket(room, proctor, null, Set.of())), room.session());
    }

    /* ---------------------------- fetch failures --------------------------- */

    @ParameterizedTest
    @EnumSource(Reason.class)
    void questionsUnavailableGivesPacketServiceUnavailable(Reason reason) {
        Room room = fx.room(Creator.GUEST, GameMode.QUIZ_BOWL_CLASSIC);
        String proctor = guestProctor(room);
        when(fx.packetClient.getPacketById(anyString()))
                .thenReturn(Mono.error(new QuestionsUnavailableException(reason, "test")));

        assertRejected(fx, setPacket(room, proctor, null, Set.of()),
                ConfigurationMessageProcessor.PACKET_SERVICE_UNAVAILABLE, room.session(), proctor);
    }

    @Test
    void tokenFailureThrownBeforeTheMonoGivesPacketServiceUnavailable() {
        Room room = fx.room(Creator.GUEST, GameMode.QUIZ_BOWL_CLASSIC);
        String proctor = guestProctor(room);
        when(fx.packetClient.getPacketById(anyString()))
                .thenThrow(new QuestionsUnavailableException(Reason.TOKEN, "keycloak down"));

        assertRejected(fx, setPacket(room, proctor, null, Set.of()),
                ConfigurationMessageProcessor.PACKET_SERVICE_UNAVAILABLE, room.session(), proctor);
    }

    @Test
    void unexpectedClientFailureGivesPacketServiceUnavailable() {
        Room room = fx.room(Creator.GUEST, GameMode.QUIZ_BOWL_CLASSIC);
        String proctor = guestProctor(room);
        when(fx.packetClient.getPacketById(anyString())).thenReturn(Mono.error(new IllegalStateException("boom")));

        assertRejected(fx, setPacket(room, proctor, null, Set.of()),
                ConfigurationMessageProcessor.PACKET_SERVICE_UNAVAILABLE, room.session(), proctor);
    }

    @Test
    void unknownPacketGivesPacketNotFound() {
        Room room = fx.room(Creator.GUEST, GameMode.QUIZ_BOWL_CLASSIC);
        String proctor = guestProctor(room);
        when(fx.packetClient.getPacketById(anyString()))
                .thenReturn(Mono.error(new PacketNotFoundException(InSessionFixture.PACKET_ID)));

        assertRejected(fx, setPacket(room, proctor, null, Set.of()),
                ConfigurationMessageProcessor.PACKET_NOT_FOUND, room.session(), proctor);
    }

    @Test
    @SuppressWarnings("unchecked")
    void tokenFailureNeverEscapesTheKafkaListener() {
        Room room = fx.room(Creator.GUEST, GameMode.QUIZ_BOWL_CLASSIC);
        String proctor = guestProctor(room);
        when(fx.packetClient.getPacketById(anyString()))
                .thenThrow(new QuestionsUnavailableException(Reason.TOKEN, "keycloak down"));

        SimpMessagingTemplate stomp = mock(SimpMessagingTemplate.class);
        SessionService sessions = mock(SessionService.class);
        when(sessions.getGameSessionById(room.session().getId())).thenReturn(room.session());
        MessageService service = new MessageService(stomp, mock(KafkaTemplate.class), sessions,
                new ConfigurationMessageProcessor(fx.packetClient, fx.policy),
                new ProgressionMessageProcessor(fx.policy),
                new GameMessageProcessor(fx.policy),
                mock(HostedSessionQuota.class), new QuotaProperties(), java.time.Clock.systemUTC());

        SockbowlInMessage message = setPacket(room, proctor, null, Set.of());
        message.setGameSession(null); // as it arrives from Kafka
        ConsumerRecord<String, SockbowlInMessage> record = new ConsumerRecord<>("game-topic", 0, 0L, null, message);

        assertThatNoException().isThrownBy(() -> service.processGameMessage(record));

        verify(sessions, never()).saveGameSession(any());
        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(stomp).convertAndSend(eq("/queue/event/" + room.session().getId() + "/" + proctor), sent.capture());
        assertThat(sent.getValue()).isInstanceOfSatisfying(ProcessError.class, error ->
                assertThat(error.getCode()).isEqualTo(ConfigurationMessageProcessor.PACKET_SERVICE_UNAVAILABLE));
        assertThat(room.session().getCurrentMatch().getPacket().getId()).isNull();
    }
}
