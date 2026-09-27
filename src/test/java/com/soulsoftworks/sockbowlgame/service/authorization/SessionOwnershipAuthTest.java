package com.soulsoftworks.sockbowlgame.service.authorization;

import com.soulsoftworks.sockbowlgame.model.entity.User;
import com.soulsoftworks.sockbowlgame.model.request.JoinGameRequest;
import com.soulsoftworks.sockbowlgame.model.response.JoinGameResponse;
import com.soulsoftworks.sockbowlgame.model.socket.in.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.SetMatchPacket;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.SetProctor;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.UpdateGameSettings;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.UpdatePlayerTeam;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.SubmitAnswer;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlMultiOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.ProcessError;
import com.soulsoftworks.sockbowlgame.model.socket.out.progression.GameSessionUpdate;
import com.soulsoftworks.sockbowlgame.model.state.*;
import com.soulsoftworks.sockbowlgame.repository.GameSessionRepository;
import com.soulsoftworks.sockbowlgame.repository.UserGameHistoryRepository;
import com.soulsoftworks.sockbowlgame.repository.UserRepository;
import com.soulsoftworks.sockbowlgame.service.GameTimerService;
import com.soulsoftworks.sockbowlgame.service.MessageService;
import com.soulsoftworks.sockbowlgame.service.SessionService;
import com.soulsoftworks.sockbowlgame.support.InSessionFixture;
import com.soulsoftworks.sockbowlgame.support.InSessionFixture.Creator;
import com.soulsoftworks.sockbowlgame.support.InSessionFixture.Room;
import com.soulsoftworks.sockbowlgame.support.StompMappingClassification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.soulsoftworks.sockbowlgame.support.StompMappingClassification.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AUTH-11: one ownership source. The owner of a session is decided once when
 * a player joins ({@link GameSession#addPlayer}) and every owner-gated message
 * is authorized by {@link GameAuthorizationPolicy#isSessionOwner}.
 *
 * <p>For every destination in {@link StompMappingClassification#OWNER_GATED}
 * (not a hand-written list), in both an authenticated-host session and a
 * guest-created one, the owner succeeds and every non-owner gets a
 * "Permission Denied" {@link ProcessError}. A destination added to
 * {@code OWNER_GATED} without a scenario here fails the test.
 */
class SessionOwnershipAuthTest {

    /* ------------------------------------------------------------------ */
    /* Who is owner                                                       */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("authenticated host is owner; an earlier guest and a second signed-in user are not")
    void authenticatedHostIsOwnerRegardlessOfJoinOrder() {
        InSessionFixture fx = new InSessionFixture();
        Room room = fx.room(Creator.AUTHENTICATED, GameMode.QUIZ_BOWL_CLASSIC);

        assertTrue(room.player("host").isGameOwner());
        assertTrue(fx.policy.isSessionOwner(room.session(), "host"));
        for (String id : List.of("guest", "other", "teammate")) {
            assertFalse(room.player(id).isGameOwner(), id + " flag");
            assertFalse(fx.policy.isSessionOwner(room.session(), id), id + " policy");
        }
        // Joining binds identity: guests carry no subject, signed-in users do.
        assertEquals(InSessionFixture.HOST_SUB, room.player("host").getKeycloakId());
        assertFalse(room.player("host").isGuest());
        assertNull(room.player("guest").getKeycloakId());
        assertTrue(room.player("guest").isGuest());
    }

    @Test
    @DisplayName("guest-created session keeps the first-joiner rule")
    void guestSessionFirstJoinerIsOwner() {
        InSessionFixture fx = new InSessionFixture();
        Room room = fx.room(Creator.GUEST, GameMode.QUIZ_BOWL_CLASSIC);

        assertTrue(room.player("first").isGameOwner());
        assertTrue(fx.policy.isSessionOwner(room.session(), "first"));
        assertFalse(fx.policy.isSessionOwner(room.session(), "second"));
        assertFalse(fx.policy.isSessionOwner(room.session(), "teammate"));
    }

    @Test
    @DisplayName("authenticated session whose host never joined has no owner (no first-joiner fallback)")
    void authenticatedSessionWithoutHostHasNoOwner() {
        GameSession session = GameSession.builder().id("S").joinCode("J").gameOwnerId("kc-host")
                .gameSettings(new GameSettings()).build();
        Player guest = session.addPlayer(JoinGameRequest.builder().playerSessionId("g").name("g").build());
        Player other = session.addPlayer(JoinGameRequest.builder().playerSessionId("o").name("o").build(), "kc-other");
        GameAuthorizationPolicy policy = new GameAuthorizationPolicy(true, null);

        assertFalse(guest.isGameOwner());
        assertFalse(other.isGameOwner());
        assertFalse(policy.isSessionOwner(session, "g"));
        assertFalse(policy.isSessionOwner(session, "o"));
    }

    @Test
    @DisplayName("SessionService: the creator joining via the authenticated path owns the session")
    void sessionServiceAuthenticatedJoinBindsOwnership() {
        GameSessionRepository sessions = mock(GameSessionRepository.class);
        UserRepository users = mock(UserRepository.class);
        UserGameHistoryRepository history = mock(UserGameHistoryRepository.class);
        SessionService service = new SessionService(sessions);
        ReflectionTestUtils.setField(service, "userRepository", users);
        ReflectionTestUtils.setField(service, "userGameHistoryRepository", history);
        when(users.findByKeycloakId(any())).thenReturn(Optional.empty());
        when(users.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) {
                u.setId(UUID.randomUUID());
            }
            return u;
        });

        GameSettings settings = new GameSettings();
        settings.setGameMode(GameMode.QUIZ_BOWL_CLASSIC);
        GameSession session = GameSession.builder().id("S").joinCode("CODE").gameOwnerId("kc-host")
                .gameSettings(settings).build();
        session.getTeamList().add(new Team());
        session.getTeamList().add(new Team());
        when(sessions.findGameSessionByJoinCode("CODE")).thenReturn(Optional.of(session));

        // A guest gets in first by code: not owner.
        JoinGameResponse guest = service.addPlayerToGameSessionWithJoinCode(
                JoinGameRequest.builder().joinCode("CODE").name("guest").build());
        // A different signed-in user: not owner.
        JoinGameResponse other = service.addAuthenticatedUserToGameSession(
                JoinGameRequest.builder().joinCode("CODE").name("other").build(), jwt("kc-other"));
        // The creator: owner.
        JoinGameResponse host = service.addAuthenticatedUserToGameSession(
                JoinGameRequest.builder().joinCode("CODE").name("host").build(), jwt("kc-host"));

        GameAuthorizationPolicy policy = new GameAuthorizationPolicy(true, null);
        assertTrue(policy.isSessionOwner(session, host.getPlayerSessionId()));
        assertFalse(policy.isSessionOwner(session, other.getPlayerSessionId()));
        assertFalse(policy.isSessionOwner(session, guest.getPlayerSessionId()));
        Player hostPlayer = session.getPlayerById(host.getPlayerSessionId());
        assertTrue(hostPlayer.isGameOwner());
        assertEquals("kc-host", hostPlayer.getKeycloakId());
        assertFalse(hostPlayer.isGuest());
        assertNotNull(hostPlayer.getUserId());
    }

    @Test
    @DisplayName("timer auto-timeouts in proctorless games are attributed to the policy's owner")
    void timerAttributesAutoTimeoutToPolicyOwner() throws Exception {
        InSessionFixture fx = new InSessionFixture();
        Room room = fx.room(Creator.AUTHENTICATED, GameMode.AUTO_PROCTOR);
        // A stale flag on a guest that disagrees with the owner subject must not be picked.
        room.player("guest").setGameOwner(true);
        GameTimerService timer = new GameTimerService(mock(SessionService.class), mock(MessageService.class),
                mock(SimpMessagingTemplate.class), fx.policy);

        Method originator = GameTimerService.class.getDeclaredMethod("timerExpiryOriginatorId", GameSession.class);
        originator.setAccessible(true);

        assertEquals("host", originator.invoke(timer, room.session()));
    }

    private static Jwt jwt(String sub) {
        return Jwt.withTokenValue("t").header("alg", "none").subject(sub)
                .claim("preferred_username", sub)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
    }

    /* ------------------------------------------------------------------ */
    /* Every owner-gated destination                                      */
    /* ------------------------------------------------------------------ */

    /** How to put a room into a state where the owner's message succeeds. */
    private record Scenario(GameMode mode, Consumer<Ctx> prepare, Consumer<Ctx> fields) {}

    /** A room plus the message being built (fields sets type-specific values on {@code msg}). */
    private static final class Ctx {
        final InSessionFixture fx;
        final Room room;
        SockbowlInMessage msg;

        Ctx(InSessionFixture fx, Room room) {
            this.fx = fx;
            this.room = room;
        }

        GameSession session() {
            return room.session();
        }

        void startMatch() {
            InSessionFixture.selectPacket(session());
            SockbowlOutMessage out = fx.dispatch(InSessionFixture.message(START_MATCH, session(), room.owner(), null));
            assertFalse(out instanceof ProcessError, "fixture: owner could not start the match: " + out);
            assertEquals(MatchState.IN_GAME, session().getCurrentMatch().getMatchState());
        }
    }

    private static final Consumer<Ctx> NOTHING = c -> {};

    private static final Map<String, Scenario> OWNER_SCENARIOS = Map.of(
            START_MATCH, new Scenario(GameMode.AUTO_PROCTOR,
                    c -> InSessionFixture.selectPacket(c.session()), NOTHING),
            END_MATCH, new Scenario(GameMode.AUTO_PROCTOR, Ctx::startMatch, NOTHING),
            ADVANCE_ROUND, new Scenario(GameMode.AUTO_PROCTOR,
                    c -> {
                        c.startMatch();
                        c.session().getCurrentRound().setRoundState(RoundState.COMPLETED);
                    }, NOTHING),
            SET_MATCH_PACKET, new Scenario(GameMode.AUTO_PROCTOR, NOTHING,
                    c -> ((SetMatchPacket) c.msg).setPacketId(InSessionFixture.PACKET_ID)),
            UPDATE_GAME_SETTINGS, new Scenario(GameMode.AUTO_PROCTOR, NOTHING,
                    c -> ((UpdateGameSettings) c.msg).setGameSettings(GameSettings.builder()
                            .gameMode(GameMode.AUTO_PROCTOR).timerSettings(new TimerSettings()).build())),
            TIMEOUT_ROUND, new Scenario(GameMode.AUTO_PROCTOR, Ctx::startMatch, NOTHING),
            START_BONUS, new Scenario(GameMode.AUTO_PROCTOR,
                    c -> {
                        c.startMatch();
                        // The teammate (alone on team 2) earns the bonus, so no
                        // non-owner under test is on the eligible team.
                        String mate = c.room.teammate();
                        c.fx.dispatch(InSessionFixture.message(PLAYER_INCOMING_BUZZ, c.session(), mate, null));
                        c.fx.dispatch(InSessionFixture.message(SUBMIT_ANSWER, c.session(), mate,
                                m -> ((SubmitAnswer) m).setAnswerText("Napoleon")));
                        assertEquals(RoundState.BONUS_PENDING, c.session().getCurrentRound().getRoundState(),
                                "fixture: bonus not pending");
                    }, NOTHING),
            UPDATE_PLAYER_TEAM, new Scenario(GameMode.QUIZ_BOWL_CLASSIC, NOTHING,
                    c -> {
                        UpdatePlayerTeam m = (UpdatePlayerTeam) c.msg;
                        m.setTargetPlayer(c.room.teammate());
                        m.setTargetTeam(c.session().getTeamList().get(0).getTeamId());
                    }),
            SET_PROCTOR, new Scenario(GameMode.QUIZ_BOWL_CLASSIC,
                    // A proctor is already seated, so nobody can self-claim either.
                    c -> InSessionFixture.makeProctor(c.session(), c.room.owner()),
                    c -> ((SetProctor) c.msg).setTargetPlayer(c.room.teammate())));

    @Test
    void everyOwnerGatedDestinationHasAScenario() {
        assertEquals(new TreeSet<>(OWNER_GATED), new TreeSet<>(OWNER_SCENARIOS.keySet()));
    }

    @TestFactory
    Stream<DynamicTest> ownerSucceedsAndNonOwnersAreDenied() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String destination : new TreeSet<>(OWNER_GATED)) {
            Scenario scenario = OWNER_SCENARIOS.get(destination);
            assertNotNull(scenario, "no scenario for " + destination);
            for (Creator creator : Creator.values()) {
                tests.add(DynamicTest.dynamicTest(destination + " [" + creator + "] owner succeeds", () -> {
                    SockbowlOutMessage out = send(scenario, creator, destination, Who.OWNER, 0);
                    assertNotNull(out);
                    assertFalse(out instanceof ProcessError, "owner rejected: " + out);
                }));
                int nonOwners = creator == Creator.AUTHENTICATED ? 2 : 1;
                for (int i = 0; i < nonOwners; i++) {
                    int index = i;
                    tests.add(DynamicTest.dynamicTest(destination + " [" + creator + "] non-owner #" + i + " denied", () -> {
                        SockbowlOutMessage out = send(scenario, creator, destination, Who.NON_OWNER, index);
                        assertTrue(InSessionFixture.isAccessDenied(out), "expected Permission Denied, got " + out);
                    }));
                }
            }
        }
        return tests.stream();
    }

    private enum Who { OWNER, NON_OWNER }

    private SockbowlOutMessage send(Scenario scenario, Creator creator, String destination, Who who, int index) {
        InSessionFixture fx = new InSessionFixture();
        Room room = fx.room(creator, scenario.mode());
        Ctx ctx = new Ctx(fx, room);
        scenario.prepare().accept(ctx);
        String originator = who == Who.OWNER ? room.owner() : room.nonOwners().get(index);
        ctx.msg = InSessionFixture.message(destination, room.session(), originator, null);
        scenario.fields().accept(ctx);
        return fx.dispatch(ctx.msg);
    }

    /* ------------------------------------------------------------------ */
    /* Session copies on the wire carry no identity (AUTH-10)             */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("update-game-settings sends sanitized copies: no identity, no answers to non-proctors")
    void updateGameSettingsResponseIsSanitized() {
        InSessionFixture fx = new InSessionFixture();
        Room room = fx.room(Creator.AUTHENTICATED, GameMode.QUIZ_BOWL_CLASSIC);
        InSessionFixture.makeProctor(room.session(), room.owner());
        selectOwnedPacket(room.session());

        SockbowlInMessage msg = InSessionFixture.message(UPDATE_GAME_SETTINGS, room.session(), room.owner(),
                m -> ((UpdateGameSettings) m).setGameSettings(GameSettings.builder()
                        .gameMode(GameMode.QUIZ_BOWL_CLASSIC).timerSettings(new TimerSettings()).build()));
        SockbowlOutMessage out = fx.dispatch(msg);

        List<GameSessionUpdate> updates = sessionUpdates(out);
        assertEquals(2, updates.size(), "one proctor copy, one player copy");
        Set<String> recipients = new HashSet<>();
        for (GameSessionUpdate update : updates) {
            assertFalse(update.getRecipients().isEmpty(), "must be targeted, not a broadcast");
            recipients.addAll(update.getRecipients());
            assertNoIdentity(update.getGameSession());
            boolean toProctor = update.getRecipients().equals(List.of(room.owner()));
            if (toProctor) {
                assertNotNull(update.getGameSession().getCurrentMatch().getPacket().getTossups());
            } else {
                assertNull(update.getGameSession().getCurrentMatch().getPacket().getTossups(),
                        "answers leaked to non-proctors");
            }
        }
        Set<String> everyone = new HashSet<>();
        room.session().getPlayerList().forEach(p -> everyone.add(p.getPlayerId()));
        assertEquals(everyone, recipients);
        // The live session is untouched.
        assertEquals(InSessionFixture.HOST_SUB, room.session().getGameOwnerId());
        assertEquals(InSessionFixture.HOST_SUB, room.player("host").getKeycloakId());
    }

    @Test
    @DisplayName("update-game-settings with no proctor broadcasts the player view only")
    void updateGameSettingsWithoutProctorBroadcastsPlayerView() {
        InSessionFixture fx = new InSessionFixture();
        Room room = fx.room(Creator.GUEST, GameMode.AUTO_PROCTOR);
        selectOwnedPacket(room.session());

        SockbowlOutMessage out = fx.dispatch(InSessionFixture.message(UPDATE_GAME_SETTINGS, room.session(), room.owner(),
                m -> ((UpdateGameSettings) m).setGameSettings(GameSettings.builder()
                        .gameMode(GameMode.AUTO_PROCTOR).timerSettings(new TimerSettings()).build())));

        List<GameSessionUpdate> updates = sessionUpdates(out);
        assertEquals(1, updates.size());
        assertNoIdentity(updates.get(0).getGameSession());
        assertNull(updates.get(0).getGameSession().getCurrentMatch().getPacket().getTossups());
    }

    @Test
    @DisplayName("get-game returns a copy with no identity for every player")
    void getGameResponseCarriesNoIdentity() {
        InSessionFixture fx = new InSessionFixture();
        Room room = fx.room(Creator.AUTHENTICATED, GameMode.QUIZ_BOWL_CLASSIC);
        InSessionFixture.makeProctor(room.session(), room.owner());
        // A finished match and a loaded packet, both carrying the author's subject.
        selectOwnedPacket(room.session());
        room.session().getPreviousMatches().add(
                com.soulsoftworks.sockbowlgame.util.DeepCopyUtil.deepCopy(room.session().getCurrentMatch(), Match.class));
        for (Player p : room.session().getPlayerList()) {
            SockbowlOutMessage out = fx.dispatch(InSessionFixture.message(GET_GAME, room.session(), p.getPlayerId(), null));
            List<GameSessionUpdate> updates = sessionUpdates(out);
            assertEquals(1, updates.size());
            assertNoIdentity(updates.get(0).getGameSession());
        }
    }

    private static List<GameSessionUpdate> sessionUpdates(SockbowlOutMessage out) {
        List<SockbowlOutMessage> all = out instanceof SockbowlMultiOutMessage multi
                ? multi.getSockbowlOutMessages() : List.of(out);
        List<GameSessionUpdate> updates = new ArrayList<>();
        for (SockbowlOutMessage m : all) {
            assertInstanceOf(GameSessionUpdate.class, m, "unexpected " + m);
            updates.add((GameSessionUpdate) m);
        }
        return updates;
    }

    /** The packet author's Keycloak subject, set on packets these tests load. */
    private static final String PACKET_OWNER_SUB = "kc-packet-author";

    /** The fixture packet with its author's subject, as a session stored before G2-02 holds it. */
    private static void selectOwnedPacket(GameSession session) {
        InSessionFixture.selectPacket(session);
        session.getCurrentMatch().getPacket().setOwnerId(PACKET_OWNER_SUB);
    }

    private static void assertNoIdentity(GameSession copy) {
        assertNull(copy.getGameOwnerId());
        // The packet author's subject (G2-02), in the current match and every previous one.
        List<Match> matches = new ArrayList<>(copy.getPreviousMatches());
        matches.add(copy.getCurrentMatch());
        for (Match match : matches) {
            if (match.getPacket() != null) {
                assertNull(match.getPacket().getOwnerId(), "packet.ownerId");
            }
        }
        assertFalse(new com.google.gson.Gson().toJson(copy).contains(PACKET_OWNER_SUB), "packet owner subject on the wire");
        List<Player> players = new ArrayList<>(copy.getPlayerList());
        copy.getTeamList().forEach(t -> players.addAll(t.getTeamPlayers()));
        assertFalse(players.isEmpty());
        for (Player p : players) {
            assertTrue(p.getKeycloakId() == null || p.getKeycloakId().isBlank(), "keycloakId " + p.getPlayerId());
            assertTrue(p.getUserId() == null || p.getUserId().isBlank(), "userId " + p.getPlayerId());
            assertTrue(p.getPlayerSecret() == null || p.getPlayerSecret().isBlank(), "secret " + p.getPlayerId());
        }
    }

    /* ------------------------------------------------------------------ */
    /* Proctor claims (G-01)                                              */
    /* ------------------------------------------------------------------ */

    /** Every answer in the fixture packet (tossups and bonus parts). */
    private static final List<String> FIXTURE_ANSWERS = List.of("Napoleon", "Shakespeare", "alpha", "beta", "gamma");

    /** Put the room's match IN_GAME the way its mode allows (proctored: seat the teammate first). */
    private static void startMatch(InSessionFixture fx, Room room) {
        InSessionFixture.selectPacket(room.session());
        String starter = room.owner();
        if (!room.session().getGameSettings().isProctorless()) {
            // The teammate is alone on team 2: move a non-owner over before seating
            // the teammate as proctor so both teams keep a player.
            String mover = room.nonOwners().get(room.nonOwners().size() - 1);
            room.session().getTeamByPlayerId(mover).removePlayerFromTeam(mover);
            room.session().getTeamByPlayerId(room.teammate()).addPlayerToTeam(room.player(mover));
            InSessionFixture.makeProctor(room.session(), room.teammate());
            starter = room.teammate();
        }
        SockbowlOutMessage out = fx.dispatch(InSessionFixture.message(START_MATCH, room.session(), starter, null));
        assertFalse(out instanceof ProcessError, "fixture: could not start the match: " + out);
        assertEquals(MatchState.IN_GAME, room.session().getCurrentMatch().getMatchState());
    }

    private static SockbowlOutMessage claimProctor(InSessionFixture fx, Room room, String sender, String target) {
        return fx.dispatch(InSessionFixture.message(SET_PROCTOR, room.session(), sender,
                m -> ((SetProctor) m).setTargetPlayer(target)));
    }

    /** get-game as {@code playerId}, serialized as it goes on the wire. */
    private static String getGameJson(InSessionFixture fx, Room room, String playerId) {
        List<GameSessionUpdate> updates = sessionUpdates(
                fx.dispatch(InSessionFixture.message(GET_GAME, room.session(), playerId, null)));
        assertEquals(1, updates.size());
        return new com.google.gson.Gson().toJson(updates.get(0).getGameSession());
    }

    private static void assertNoAnswers(String json) {
        for (String answer : FIXTURE_ANSWERS) {
            assertFalse(json.contains(answer), "answer '" + answer + "' leaked: " + json);
        }
    }

    @TestFactory
    @DisplayName("SetProctor(self) from a non-owner is refused in proctorless modes and once a match has started")
    Stream<DynamicTest> proctorSelfClaimRefusedOutsideClassicConfig() {
        List<DynamicTest> tests = new ArrayList<>();
        List<GameMode> modes = List.of(GameMode.AUTO_PROCTOR, GameMode.FREE_FOR_ALL, GameMode.SINGLE_PLAYER,
                GameMode.QUIZ_BOWL_CLASSIC);
        for (Creator creator : Creator.values()) {
            for (GameMode mode : modes) {
                for (MatchState state : List.of(MatchState.CONFIG, MatchState.IN_GAME)) {
                    if (mode == GameMode.QUIZ_BOWL_CLASSIC && state == MatchState.CONFIG) {
                        continue; // first-come claim, allowed (see below)
                    }
                    tests.add(DynamicTest.dynamicTest(mode + " " + state + " [" + creator + "]", () -> {
                        InSessionFixture fx = new InSessionFixture();
                        Room room = fx.room(creator, mode);
                        InSessionFixture.selectPacket(room.session());
                        if (state == MatchState.IN_GAME) {
                            startMatch(fx, room);
                            if (mode == GameMode.QUIZ_BOWL_CLASSIC) {
                                // The proctor has left: the seat is empty mid-match.
                                room.player(room.teammate()).setPlayerMode(PlayerMode.SPECTATOR);
                                assertNull(room.session().getProctor());
                            }
                        }
                        String claimant = room.nonOwners().get(0);

                        SockbowlOutMessage out = claimProctor(fx, room, claimant, claimant);

                        assertInstanceOf(ProcessError.class, out, "self-claim accepted: " + out);
                        assertNotEquals(PlayerMode.PROCTOR, room.player(claimant).getPlayerMode());
                        assertNoAnswers(getGameJson(fx, room, claimant));
                    }));
                }
            }
        }
        return tests.stream();
    }

    @Test
    @DisplayName("Proctorless modes have no proctor role: the owner cannot seat one either")
    void ownerCannotSeatAProctorInProctorlessModes() {
        for (GameMode mode : List.of(GameMode.AUTO_PROCTOR, GameMode.FREE_FOR_ALL, GameMode.SINGLE_PLAYER)) {
            InSessionFixture fx = new InSessionFixture();
            Room room = fx.room(Creator.AUTHENTICATED, mode);
            InSessionFixture.selectPacket(room.session());
            assertTrue(InSessionFixture.isAccessDenied(claimProctor(fx, room, room.owner(), room.teammate())), mode.name());
            assertTrue(InSessionFixture.isAccessDenied(claimProctor(fx, room, room.owner(), room.owner())), mode.name());
            assertNull(room.session().getProctor(), mode.name());
        }
    }

    @Test
    @DisplayName("QUIZ_BOWL_CLASSIC in CONFIG keeps the first-come proctor claim")
    void classicConfigFirstComeClaimStillWorks() {
        InSessionFixture fx = new InSessionFixture();
        Room room = fx.room(Creator.AUTHENTICATED, GameMode.QUIZ_BOWL_CLASSIC);
        InSessionFixture.selectPacket(room.session());
        String claimant = room.nonOwners().get(0);

        SockbowlOutMessage out = claimProctor(fx, room, claimant, claimant);

        assertFalse(out instanceof ProcessError, "first-come claim refused: " + out);
        assertEquals(PlayerMode.PROCTOR, room.player(claimant).getPlayerMode());
        // A second claimant is refused while the seat is taken.
        String second = room.nonOwners().get(1);
        assertTrue(InSessionFixture.isAccessDenied(claimProctor(fx, room, second, second)));
    }

    @Test
    @DisplayName("QUIZ_BOWL_CLASSIC mid-match: the owner may still replace the proctor")
    void classicOwnerMayReplaceTheProctorMidMatch() {
        InSessionFixture fx = new InSessionFixture();
        Room room = fx.room(Creator.AUTHENTICATED, GameMode.QUIZ_BOWL_CLASSIC);
        startMatch(fx, room);
        String replacement = room.nonOwners().get(0);

        SockbowlOutMessage out = claimProctor(fx, room, room.owner(), replacement);

        assertFalse(out instanceof ProcessError, "owner reassignment refused: " + out);
        assertEquals(PlayerMode.PROCTOR, room.player(replacement).getPlayerMode());
        assertEquals(PlayerMode.SPECTATOR, room.player(room.teammate()).getPlayerMode());
        assertNull(room.session().getTeamByPlayerId(replacement), "new proctor must leave their team");
        // The match goes on: its packet stays.
        assertEquals(InSessionFixture.PACKET_ID, room.session().getCurrentMatch().getPacket().getId());
    }

    @Test
    @DisplayName("Switching a proctored room to a proctorless mode unseats the proctor")
    void switchingToProctorlessModeUnseatsTheProctor() {
        InSessionFixture fx = new InSessionFixture();
        Room room = fx.room(Creator.AUTHENTICATED, GameMode.QUIZ_BOWL_CLASSIC);
        InSessionFixture.selectPacket(room.session());
        String claimant = room.nonOwners().get(0);
        assertFalse(claimProctor(fx, room, claimant, claimant) instanceof ProcessError);

        SockbowlOutMessage out = fx.dispatch(InSessionFixture.message(UPDATE_GAME_SETTINGS, room.session(), claimant,
                m -> ((UpdateGameSettings) m).setGameSettings(GameSettings.builder()
                        .gameMode(GameMode.AUTO_PROCTOR).bonusesEnabled(true)
                        .timerSettings(new TimerSettings()).build())));
        assertFalse(out instanceof ProcessError, "settings update refused: " + out);

        assertNull(room.session().getProctor());
        assertEquals(PlayerMode.SPECTATOR, room.player(claimant).getPlayerMode());
        assertNoAnswers(getGameJson(fx, room, claimant));

        // The packet loaded before the switch is gone (G2-03); the owner loads it
        // again for the proctorless game.
        assertNull(room.session().getCurrentMatch().getPacket().getId());
        SockbowlOutMessage loaded = fx.dispatch(InSessionFixture.message(SET_MATCH_PACKET, room.session(), room.owner(), null));
        assertFalse(loaded instanceof ProcessError, "packet load refused: " + loaded);

        // And once the owner starts the match, the former proctor still reads no answers.
        SockbowlOutMessage started = fx.dispatch(InSessionFixture.message(START_MATCH, room.session(), room.owner(), null));
        assertFalse(started instanceof ProcessError, "start refused: " + started);
        assertNoAnswers(getGameJson(fx, room, claimant));
    }

    @Test
    @DisplayName("G2-02: SetMatchPacket checks the author's subject, then stores the packet without it")
    void setMatchPacketDropsThePacketOwnerSubject() {
        InSessionFixture fx = new InSessionFixture();
        when(fx.packetClient.getPacketById(any())).thenAnswer(inv -> {
            var packet = InSessionFixture.packet();
            packet.setOwnerId(PACKET_OWNER_SUB);
            packet.setOwnerDisplayName("Packet Author");
            return reactor.core.publisher.Mono.just(packet);
        });
        Room room = fx.room(Creator.AUTHENTICATED, GameMode.QUIZ_BOWL_CLASSIC);
        InSessionFixture.makeProctor(room.session(), room.teammate());

        SockbowlOutMessage out = fx.dispatch(InSessionFixture.message(SET_MATCH_PACKET, room.session(), room.teammate(), null));

        assertFalse(out instanceof ProcessError, "packet load refused: " + out);
        assertEquals(InSessionFixture.PACKET_ID, room.session().getCurrentMatch().getPacket().getId());
        assertNull(room.session().getCurrentMatch().getPacket().getOwnerId());
        assertNull(room.session().getCurrentMatch().getPacket().getOwnerDisplayName());
    }

    /* ------------------------------------------------------------------ */
    /* A proctor seat change in CONFIG clears the packet (G2-03)          */
    /* ------------------------------------------------------------------ */

    private static SockbowlOutMessage moveToTeam(InSessionFixture fx, Room room, String sender, String target, String teamId) {
        return fx.dispatch(InSessionFixture.message(UPDATE_PLAYER_TEAM, room.session(), sender, m -> {
            ((UpdatePlayerTeam) m).setTargetPlayer(target);
            ((UpdatePlayerTeam) m).setTargetTeam(teamId);
        }));
    }

    private static SockbowlOutMessage loadPacket(InSessionFixture fx, Room room, String sender) {
        return fx.dispatch(InSessionFixture.message(SET_MATCH_PACKET, room.session(), sender, null));
    }

    private static String packetId(Room room) {
        return room.session().getCurrentMatch().getPacket().getId();
    }

    /** The out-message includes a broadcast MatchPacketUpdate with no packet. */
    private static void assertPacketResetBroadcast(SockbowlOutMessage out) {
        List<SockbowlOutMessage> all = out instanceof SockbowlMultiOutMessage multi
                ? multi.getSockbowlOutMessages() : List.of(out);
        assertTrue(all.stream().anyMatch(m -> m instanceof com.soulsoftworks.sockbowlgame.model.socket.out.config.MatchPacketUpdate u
                        && u.getPacketId() == null && u.getTossupCount() == 0 && u.getRecipients().isEmpty()),
                "no packet reset broadcast in " + out);
    }

    @Test
    @DisplayName("G2-03: proctor leaves for a team, a player claims the seat, returns to a team, the proctor reclaims: no answers, packet reloaded")
    void proctorLeavesThenClaimThenRejoinReadsNoAnswers() {
        InSessionFixture fx = new InSessionFixture();
        Room room = fx.room(Creator.GUEST, GameMode.QUIZ_BOWL_CLASSIC);
        String proctor = room.teammate();
        String cheater = room.nonOwners().get(0);
        String team2 = room.session().getTeamList().get(1).getTeamId();

        // The proctor claims the seat and loads the packet.
        assertFalse(claimProctor(fx, room, proctor, proctor) instanceof ProcessError);
        assertFalse(loadPacket(fx, room, proctor) instanceof ProcessError);
        assertEquals(InSessionFixture.PACKET_ID, packetId(room));
        assertTrue(getGameJson(fx, room, proctor).contains("Shakespeare"), "the proctor sees the packet");

        // The proctor steps down by joining a team: the packet goes with the seat.
        SockbowlOutMessage down = moveToTeam(fx, room, proctor, proctor, team2);
        assertFalse(down instanceof ProcessError, "step down refused: " + down);
        assertNull(room.session().getProctor());
        assertNull(packetId(room), "the packet must not outlive the proctor who loaded it");
        assertPacketResetBroadcast(down);

        // A player claims the empty seat and reads nothing.
        assertFalse(claimProctor(fx, room, cheater, cheater) instanceof ProcessError);
        assertEquals(PlayerMode.PROCTOR, room.player(cheater).getPlayerMode());
        assertNoAnswers(getGameJson(fx, room, cheater));

        // ...then returns to a team as a buzzer (the one the proctor will leave), still having read nothing.
        SockbowlOutMessage back = moveToTeam(fx, room, cheater, cheater, team2);
        assertFalse(back instanceof ProcessError, "return to team refused: " + back);
        assertEquals(PlayerMode.BUZZER, room.player(cheater).getPlayerMode());
        assertNoAnswers(getGameJson(fx, room, cheater));

        // The original proctor reclaims the seat: the match cannot start until
        // the packet is loaded again, by the proctor.
        assertFalse(claimProctor(fx, room, proctor, proctor) instanceof ProcessError);
        assertNull(packetId(room));
        SockbowlOutMessage refused = fx.dispatch(InSessionFixture.message(START_MATCH, room.session(), proctor, null));
        assertInstanceOf(ProcessError.class, refused);
        assertFalse(loadPacket(fx, room, proctor) instanceof ProcessError);
        SockbowlOutMessage started = fx.dispatch(InSessionFixture.message(START_MATCH, room.session(), proctor, null));
        assertFalse(started instanceof ProcessError, "start refused: " + started);
        assertNoAnswers(getGameJson(fx, room, cheater));
    }

    @Test
    @DisplayName("G2-03: a packet loaded by one proctor never reaches a player who claims the seat after them")
    void claimAfterProctorLeftWithPacketLoadedReadsNoAnswers() {
        InSessionFixture fx = new InSessionFixture();
        Room room = fx.room(Creator.AUTHENTICATED, GameMode.QUIZ_BOWL_CLASSIC);
        String proctor = room.teammate();
        String cheater = room.nonOwners().get(0);
        assertFalse(claimProctor(fx, room, proctor, proctor) instanceof ProcessError);
        assertFalse(loadPacket(fx, room, proctor) instanceof ProcessError);

        // The proctor goes to the spectators.
        SockbowlOutMessage out = moveToTeam(fx, room, proctor, proctor, Team.SPECTATOR_TEAM);
        assertFalse(out instanceof ProcessError, "move refused: " + out);
        assertPacketResetBroadcast(out);

        assertFalse(claimProctor(fx, room, cheater, cheater) instanceof ProcessError);
        assertNull(packetId(room));
        assertNoAnswers(getGameJson(fx, room, cheater));
    }

    @Test
    @DisplayName("G2-03: a seat filled while a packet is already loaded (legacy session) clears it")
    void claimingAnEmptySeatWithAPacketLoadedClearsIt() {
        InSessionFixture fx = new InSessionFixture();
        Room room = fx.room(Creator.GUEST, GameMode.QUIZ_BOWL_CLASSIC);
        InSessionFixture.selectPacket(room.session());
        String claimant = room.nonOwners().get(0);

        SockbowlOutMessage out = claimProctor(fx, room, claimant, claimant);

        assertFalse(out instanceof ProcessError, "claim refused: " + out);
        assertPacketResetBroadcast(out);
        assertNull(packetId(room));
        assertNoAnswers(getGameJson(fx, room, claimant));
    }

    @Test
    @DisplayName("G2-03: the owner handing the seat to another player in CONFIG clears the packet")
    void ownerReassigningTheSeatInConfigClearsThePacket() {
        InSessionFixture fx = new InSessionFixture();
        Room room = fx.room(Creator.AUTHENTICATED, GameMode.QUIZ_BOWL_CLASSIC);
        String proctor = room.teammate();
        String next = room.nonOwners().get(0);
        assertFalse(claimProctor(fx, room, proctor, proctor) instanceof ProcessError);
        assertFalse(loadPacket(fx, room, proctor) instanceof ProcessError);

        // Re-seating the same proctor changes nothing.
        SockbowlOutMessage same = claimProctor(fx, room, room.owner(), proctor);
        assertInstanceOf(com.soulsoftworks.sockbowlgame.model.socket.out.config.PlayerRosterUpdate.class, same);
        assertEquals(InSessionFixture.PACKET_ID, packetId(room));

        SockbowlOutMessage out = claimProctor(fx, room, room.owner(), next);

        assertFalse(out instanceof ProcessError, "reassignment refused: " + out);
        assertEquals(PlayerMode.PROCTOR, room.player(next).getPlayerMode());
        assertPacketResetBroadcast(out);
        assertNull(packetId(room));
        assertNoAnswers(getGameJson(fx, room, next));
    }

    @Test
    @DisplayName("G2-03: the owner moving the proctor onto a team in CONFIG clears the packet")
    void ownerMovingTheProctorToATeamClearsThePacket() {
        InSessionFixture fx = new InSessionFixture();
        Room room = fx.room(Creator.AUTHENTICATED, GameMode.QUIZ_BOWL_CLASSIC);
        String proctor = room.teammate();
        assertFalse(claimProctor(fx, room, proctor, proctor) instanceof ProcessError);
        assertFalse(loadPacket(fx, room, proctor) instanceof ProcessError);

        SockbowlOutMessage out = moveToTeam(fx, room, room.owner(), proctor,
                room.session().getTeamList().get(0).getTeamId());

        assertFalse(out instanceof ProcessError, "move refused: " + out);
        assertPacketResetBroadcast(out);
        assertNull(packetId(room));
        assertNoAnswers(getGameJson(fx, room, proctor));
    }

    @Test
    @DisplayName("G2-03: a non-proctor changing team leaves the packet alone")
    void aBuzzerChangingTeamKeepsThePacket() {
        InSessionFixture fx = new InSessionFixture();
        Room room = fx.room(Creator.AUTHENTICATED, GameMode.QUIZ_BOWL_CLASSIC);
        String proctor = room.teammate();
        assertFalse(claimProctor(fx, room, proctor, proctor) instanceof ProcessError);
        assertFalse(loadPacket(fx, room, proctor) instanceof ProcessError);
        String mover = room.nonOwners().get(0);

        SockbowlOutMessage out = moveToTeam(fx, room, mover, mover, room.session().getTeamList().get(1).getTeamId());

        assertInstanceOf(com.soulsoftworks.sockbowlgame.model.socket.out.config.PlayerRosterUpdate.class, out);
        assertEquals(InSessionFixture.PACKET_ID, packetId(room));
    }

    @Test
    @DisplayName("G2-03: switching a proctorless room to QUIZ_BOWL_CLASSIC clears the owner's packet before anyone can claim the seat")
    void switchingToClassicClearsThePacket() {
        InSessionFixture fx = new InSessionFixture();
        Room room = fx.room(Creator.AUTHENTICATED, GameMode.AUTO_PROCTOR);
        assertFalse(loadPacket(fx, room, room.owner()) instanceof ProcessError);
        assertEquals(InSessionFixture.PACKET_ID, packetId(room));

        SockbowlOutMessage out = fx.dispatch(InSessionFixture.message(UPDATE_GAME_SETTINGS, room.session(), room.owner(),
                m -> ((UpdateGameSettings) m).setGameSettings(GameSettings.builder()
                        .gameMode(GameMode.QUIZ_BOWL_CLASSIC).bonusesEnabled(true)
                        .timerSettings(new TimerSettings()).build())));
        assertFalse(out instanceof ProcessError, "settings update refused: " + out);
        assertNull(packetId(room));

        String claimant = room.nonOwners().get(0);
        assertFalse(claimProctor(fx, room, claimant, claimant) instanceof ProcessError);
        assertNoAnswers(getGameJson(fx, room, claimant));
    }

    @Test
    @DisplayName("G2-04 (recorded decision): the owner may take the seat mid-match, but then sits off every team until CONFIG")
    void ownerTakingTheSeatMidMatchCannotBuzzWithTheAnswers() {
        InSessionFixture fx = new InSessionFixture();
        Room room = fx.room(Creator.AUTHENTICATED, GameMode.QUIZ_BOWL_CLASSIC);
        startMatch(fx, room);
        String owner = room.owner();
        assertNotNull(room.session().getTeamByPlayerId(owner));

        SockbowlOutMessage out = claimProctor(fx, room, owner, owner);

        assertFalse(out instanceof ProcessError, "owner seat refused: " + out);
        assertEquals(PlayerMode.PROCTOR, room.player(owner).getPlayerMode());
        assertEquals(PlayerMode.SPECTATOR, room.player(room.teammate()).getPlayerMode());
        assertNull(room.session().getTeamByPlayerId(owner), "the owner leaves their team with the seat");
        // Handing the seat back leaves the owner a spectator, and team changes are CONFIG-only.
        assertFalse(claimProctor(fx, room, owner, room.teammate()) instanceof ProcessError);
        assertEquals(PlayerMode.SPECTATOR, room.player(owner).getPlayerMode());
        SockbowlOutMessage rejoin = moveToTeam(fx, room, owner, owner, room.session().getTeamList().get(0).getTeamId());
        assertInstanceOf(ProcessError.class, rejoin);
        assertNull(room.session().getTeamByPlayerId(owner));
    }
}
