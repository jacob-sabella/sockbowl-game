package com.soulsoftworks.sockbowlgame.support;

import com.soulsoftworks.sockbowlgame.client.PacketClient;
import com.soulsoftworks.sockbowlgame.model.request.JoinGameRequest;
import com.soulsoftworks.sockbowlgame.model.socket.in.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.SetMatchPacket;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.UpdateGameSettings;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.SubmitAnswer;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.ProcessError;
import com.soulsoftworks.sockbowlgame.model.state.*;
import com.soulsoftworks.sockbowlgame.service.authorization.GameAuthorizationPolicy;
import com.soulsoftworks.sockbowlgame.service.processor.ConfigurationMessageProcessor;
import com.soulsoftworks.sockbowlgame.service.processor.GameMessageProcessor;
import com.soulsoftworks.sockbowlgame.service.processor.MessageProcessor;
import com.soulsoftworks.sockbowlgame.service.processor.ProgressionMessageProcessor;
import com.soulsoftworks.sockbowlgame.util.PacketBuilderHelper;
import com.soulsoftworks.sockbowlquestions.models.nodes.Bonus;
import com.soulsoftworks.sockbowlquestions.models.nodes.BonusPart;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsBonus;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsTossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real game sessions, joined through {@link GameSession#addPlayer} (the path
 * both REST joins use), and the three real message processors wired with an
 * auth-on {@link GameAuthorizationPolicy}. Shared by the in-session
 * authorization tests so every {@link StompMappingClassification} destination
 * is exercised against the same rules the Kafka consumer applies.
 */
public final class InSessionFixture {

    public static final String HOST_SUB = "kc-host";
    public static final String OTHER_SUB = "kc-other";
    public static final String PACKET_ID = "PKT-1";

    /** Who created the session. */
    public enum Creator { AUTHENTICATED, GUEST }

    public final GameAuthorizationPolicy policy;
    public final PacketClient packetClient = mock(PacketClient.class);
    /** Shared by every room this fixture builds, as the Redis bindings are by every game. */
    public final InMemoryEphemeralPacketBindings ephemeralBindings = new InMemoryEphemeralPacketBindings();
    private final List<MessageProcessor> processors;

    /** Auth on: the in-session authorization rules under test. */
    public InSessionFixture() {
        this(true);
    }

    /** {@code authEnabled=false} mirrors {@code sockbowl.auth.enabled=false} (local dev). */
    public InSessionFixture(boolean authEnabled) {
        policy = new GameAuthorizationPolicy(authEnabled, null);
        when(packetClient.getPacketById(any())).thenAnswer(inv -> Mono.just(packet()));
        processors = List.of(
                new ConfigurationMessageProcessor(packetClient, policy, ephemeralBindings),
                new GameMessageProcessor(policy),
                new ProgressionMessageProcessor(policy));
    }

    /**
     * A session and the ids of the players in it.
     *
     * @param session   the live session
     * @param owner     the player who owns it
     * @param nonOwners every other player that must not be treated as owner
     * @param teammate  a non-owner seated alone on the second team (the target
     *                  of "for another player" messages, and the proctor in
     *                  proctored rooms); not in {@code nonOwners}
     */
    public record Room(GameSession session, String owner, List<String> nonOwners, String teammate) {
        public Player player(String id) {
            return session.getPlayerById(id);
        }
    }

    /**
     * Build a room in CONFIG state.
     * <ul>
     *   <li>AUTHENTICATED: {@code gameOwnerId = kc-host}. A guest and a second
     *       signed-in user join <em>before</em> the host, so the first-joiner rule
     *       would pick the wrong owner; then the host, then the teammate.</li>
     *   <li>GUEST: {@code gameOwnerId = null}. The first guest to join owns it.</li>
     * </ul>
     * Everyone but the teammate is a BUZZER on team 1; the teammate is alone on team 2.
     */
    public Room room(Creator creator, GameMode mode) {
        GameSettings settings = GameSettings.builder()
                .gameMode(mode)
                .bonusesEnabled(true)
                .timerSettings(new TimerSettings())
                .build();
        GameSession session = GameSession.builder()
                .id("SESSION-" + creator + "-" + mode)
                .joinCode("JOIN")
                .gameOwnerId(creator == Creator.AUTHENTICATED ? HOST_SUB : null)
                .gameSettings(settings)
                .build();
        Team team1 = new Team();
        team1.setTeamName("Team 1");
        Team team2 = new Team();
        team2.setTeamName("Team 2");
        session.getTeamList().add(team1);
        session.getTeamList().add(team2);

        String owner;
        List<String> nonOwners = new ArrayList<>();
        if (creator == Creator.AUTHENTICATED) {
            nonOwners.add(join(session, "guest", null));
            nonOwners.add(join(session, "other", OTHER_SUB));
            owner = join(session, "host", HOST_SUB);
        } else {
            owner = join(session, "first", null);
            nonOwners.add(join(session, "second", null));
        }
        String teammate = join(session, "teammate", null);

        for (Player p : session.getPlayerList()) {
            p.setPlayerMode(PlayerMode.BUZZER);
            (p.getPlayerId().equals(teammate) ? team2 : team1).addPlayerToTeam(p);
        }
        return new Room(session, owner, List.copyOf(nonOwners), teammate);
    }

    private static String join(GameSession session, String id, String keycloakId) {
        JoinGameRequest request = JoinGameRequest.builder().playerSessionId(id).name(id).joinCode("JOIN").build();
        return session.addPlayer(request, keycloakId).getPlayerId();
    }

    /** Select the fixture packet for the match, as SetMatchPacket would. */
    public static void selectPacket(GameSession session) {
        session.getCurrentMatch().setPacket(packet());
    }

    /** Make {@code playerId} the proctor (off every team), as SetProctor would. */
    public static void makeProctor(GameSession session, String playerId) {
        Player p = session.getPlayerById(playerId);
        p.setPlayerMode(PlayerMode.PROCTOR);
        Team team = session.getTeamByPlayerId(playerId);
        if (team != null) {
            team.removePlayerFromTeam(playerId);
        }
    }

    /**
     * Build a message of {@code destination}'s type for {@code session}, sent by
     * {@code originator}, with any type-specific fields filled by {@code fields}.
     */
    public static SockbowlInMessage message(String destination, GameSession session, String originator,
                                            Consumer<SockbowlInMessage> fields) {
        Class<? extends SockbowlInMessage> type = StompMappingClassification.MESSAGE_TYPES.get(destination);
        if (type == null) {
            throw new IllegalArgumentException("No message type for " + destination);
        }
        try {
            SockbowlInMessage message = type.getDeclaredConstructor().newInstance();
            message.setGameSession(session);
            message.setGameSessionId(session.getId());
            message.setOriginatingPlayerId(originator);
            applyDefaults(message, session);
            if (fields != null) {
                fields.accept(message);
            }
            return message;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Well-formed bodies for messages that carry required fields; {@code fields} may override them. */
    private static void applyDefaults(SockbowlInMessage message, GameSession session) {
        if (message instanceof SetMatchPacket setMatchPacket) {
            setMatchPacket.setPacketId(PACKET_ID);
        } else if (message instanceof UpdateGameSettings updateGameSettings) {
            updateGameSettings.setGameSettings(GameSettings.builder()
                    .gameMode(session.getGameSettings().getGameMode())
                    .timerSettings(new TimerSettings())
                    .build());
        } else if (message instanceof SubmitAnswer submitAnswer) {
            submitAnswer.setAnswerText("");
        }
    }

    /** Route a message to whichever processor handles its type (as MessageService does). */
    public SockbowlOutMessage dispatch(SockbowlInMessage message) {
        for (MessageProcessor processor : processors) {
            SockbowlOutMessage out = processor.processMessage(message);
            if (out != null) {
                return out;
            }
        }
        throw new IllegalStateException("No processor handles " + message.getClass().getSimpleName());
    }

    /** True for the {@link ProcessError#accessDeniedMessage} rejection. */
    public static boolean isAccessDenied(SockbowlOutMessage out) {
        return out instanceof ProcessError error
                && error.getError() != null
                && error.getError().endsWith(": Permission Denied");
    }

    /** Two tossups, each with a three-part bonus. */
    public static Packet packet() {
        List<ContainsTossup> tossups = new ArrayList<>();
        tossups.add(PacketBuilderHelper.createTossup(1, 0,
                Tossup.builder().question("This French emperor lost at Waterloo.")
                        .answer("<b><u>Napoleon</u></b> Bonaparte").build()));
        tossups.add(PacketBuilderHelper.createTossup(2, 1,
                Tossup.builder().question("This English playwright wrote Hamlet.")
                        .answer("<b><u>Shakespeare</u></b>").build()));
        List<ContainsBonus> bonuses = new ArrayList<>();
        bonuses.add(PacketBuilderHelper.createBonus(1, 0, threePartBonus()));
        bonuses.add(PacketBuilderHelper.createBonus(2, 1, threePartBonus()));
        return PacketBuilderHelper.createPacket(PACKET_ID, "Fixture Packet",
                PacketBuilderHelper.createDifficulty("D1", "Regionals"), tossups, bonuses);
    }

    private static Bonus threePartBonus() {
        return Bonus.builder()
                .preamble("A three-part bonus.")
                .bonusParts(new ArrayList<>(List.of(part(0, "<u>alpha</u>"), part(1, "<u>beta</u>"), part(2, "<u>gamma</u>"))))
                .build();
    }

    private static HasBonusPart part(int order, String answer) {
        return HasBonusPart.builder()
                .order(order)
                .bonusPart(BonusPart.builder().question("q" + order).answer(answer).build())
                .build();
    }
}
