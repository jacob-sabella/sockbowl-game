package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.client.PacketClient;
import com.soulsoftworks.sockbowlgame.client.PacketNotFoundException;
import com.soulsoftworks.sockbowlgame.client.QuestionsUnavailableException;
import com.soulsoftworks.sockbowlquestions.models.nodes.Bonus;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsBonus;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsTossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart;
import com.soulsoftworks.sockbowlgame.model.socket.in.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.GetGameState;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.SetMatchPacket;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.SetProctor;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.UpdateGameSettings;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.UpdatePlayerTeam;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlMultiOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.config.MatchPacketUpdate;
import com.soulsoftworks.sockbowlgame.model.socket.out.config.PlayerRosterUpdate;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.ProcessError;
import com.soulsoftworks.sockbowlgame.model.socket.out.progression.GameSessionUpdate;
import com.soulsoftworks.sockbowlgame.model.state.*;
import com.soulsoftworks.sockbowlgame.service.authorization.GameAuthorizationPolicy;
import com.soulsoftworks.sockbowlgame.service.packet.EphemeralPacketBindings;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
public class ConfigurationMessageProcessor extends MessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationMessageProcessor.class);

    /** SetMatchPacket error codes ({@link ProcessError#getCode()}). */
    public static final String PACKET_NOT_FOUND = "PACKET_NOT_FOUND";
    public static final String PACKET_NOT_AVAILABLE = "PACKET_NOT_AVAILABLE";
    public static final String PACKET_SERVICE_UNAVAILABLE = "PACKET_SERVICE_UNAVAILABLE";
    public static final String PACKET_EMPTY = "PACKET_EMPTY";
    /**
     * The player held the proctor seat while the loaded packet was loaded,
     * so read its answers, and may not play on it (R3-G2-03). Also returned
     * by start-match ({@link ProgressionMessageProcessor}).
     */
    public static final String PACKET_ANSWERS_SEEN = "PACKET_ANSWERS_SEEN";

    private final PacketClient packetClient;
    private final GameAuthorizationPolicy authorizationPolicy;
    private final EphemeralPacketBindings ephemeralPacketBindings;

    public ConfigurationMessageProcessor(PacketClient packetClient,
                                         GameAuthorizationPolicy authorizationPolicy,
                                         EphemeralPacketBindings ephemeralPacketBindings) {
        this.packetClient = packetClient;
        this.authorizationPolicy = authorizationPolicy;
        this.ephemeralPacketBindings = ephemeralPacketBindings;
    }

    @Override
    protected void initializeProcessorMapping() {
        processorMapping.registerProcessor(UpdatePlayerTeam.class, this::changeTeamForTargetPlayer);
        processorMapping.registerProcessor(SetProctor.class, this::setPlayerAsProctor);
        processorMapping.registerProcessor(SetMatchPacket.class, this::setPacketForMatch);
        processorMapping.registerProcessor(GetGameState.class, this::sendGameState);
        processorMapping.registerProcessor(UpdateGameSettings.class, this::updateGameSettings);
    }

    /**
     * Changes the team of a target player.
     *
     * @param updatePlayerTeamMessage The incoming message that contains all necessary information to change a player's team.
     * @return SockbowlOutMessage which might be an error message or a success message. The implementation of this
     * message is not shown in this method.
     */
    public SockbowlOutMessage changeTeamForTargetPlayer(SockbowlInMessage updatePlayerTeamMessage) {
        // Casting the incoming message to the specific type which includes player and team info
        UpdatePlayerTeam message = (UpdatePlayerTeam) updatePlayerTeamMessage;

        // Retrieve the game session from the incoming message
        GameSession gameSession = message.getGameSession();

        // Get values from the session
        Team targetTeam = new Team();
        if (!message.getTargetTeam().equals(Team.SPECTATOR_TEAM)) {
            targetTeam = gameSession.findTeamWithId(message.getTargetTeam());
        }
        Team currentTeam = gameSession.getTeamByPlayerId(message.getTargetPlayer());
        Player targetPlayer = gameSession.getPlayerById(message.getTargetPlayer());

        // Only usable in the CONFIG state
        if (gameSession.getCurrentMatch().getMatchState() != MatchState.CONFIG) {
            return ProcessError.wrongStateMessage(message);
        }

        // Checking if the target team or target player is not found
        if (targetTeam == null || targetPlayer == null) {
            // If any is not found, returning an error message
            return ProcessError.builder().recipient(updatePlayerTeamMessage.getOriginatingPlayerId()).error("Target team or player does not exist").build();
        }

        // Validating if the player who initiated the request is allowed to change the team of the target player
        if (!authorizationPolicy.canChangeTeam(gameSession, updatePlayerTeamMessage.getOriginatingPlayerId(), message.getTargetPlayer())) {
            // If not, returning an error message
            return ProcessError.accessDeniedMessage(message);
        }


        // Checking if the player is already in the target team
        if (currentTeam != null && currentTeam.getTeamId().equals(targetTeam.getTeamId())) {
            // If yes, returning an error message
            return ProcessError.builder().error("Player already on team").recipient(updatePlayerTeamMessage.getOriginatingPlayerId()).build();
        }

        // A proctor who moves to a team (or is moved) gives up the seat (G2-03).
        boolean vacatesProctorSeat = targetPlayer.getPlayerMode() == PlayerMode.PROCTOR;

        // A player who proctored the loaded packet has read its answers, so
        // may not play on it (R3-G2-03). A proctor leaving the seat takes the
        // packet with them (it is cleared below), so there is nothing to refuse.
        if (!vacatesProctorSeat && !message.getTargetTeam().equals(Team.SPECTATOR_TEAM)
                && gameSession.hasProctoredPacket(gameSession.loadedPacketId(), targetPlayer.getPlayerId())) {
            return ProcessError.coded(message, PACKET_ANSWERS_SEEN,
                    "This player proctored the loaded packet and has seen its answers, so cannot join a team for it");
        }

        // If the player is currently in a team, remove the player from the current team
        if (currentTeam != null && !currentTeam.getTeamId().equals(Team.SPECTATOR_TEAM)) {
            currentTeam.getTeamPlayers().remove(targetPlayer);
        }

        // Add player to the target team
        targetTeam.addPlayerToTeam(targetPlayer);

        if (message.getTargetTeam().equals(Team.SPECTATOR_TEAM)) {
            // Change player PlayerMode to spectator
            targetPlayer.setPlayerMode(PlayerMode.SPECTATOR);
        } else {
            // Change player PlayerMode to buzzer
            targetPlayer.setPlayerMode(PlayerMode.BUZZER);
        }

        // Return a PlayerRosterUpdate, plus the packet reset if leaving the seat cleared it
        return withPacketReset(PlayerRosterUpdate.fromGameSession(gameSession),
                vacatesProctorSeat && clearPacketOnProctorChange(gameSession));
    }

    /**
     * Sets the packet for the current match in the game session.
     * <p>
     * This method validates the access level of the originating player to ensure they have the
     * necessary permissions to set the packet for the match. If access is denied, an error
     * message is returned. If the packet with the provided ID doesn't exist, an error message
     * is also returned.
     * <p>
     * If everything is valid, the packet is set for the current match and a success message
     * (MatchPacketUpdate) is returned.
     *
     * @param setMatchPacketMessage The incoming message that contains the necessary information
     *                              to set a packet for a match.
     * @return SockbowlOutMessage which might be an error message or a MatchPacketUpdate message.
     */
    public SockbowlOutMessage setPacketForMatch(SockbowlInMessage setMatchPacketMessage) {
        // Cast the incoming message to the specific type
        SetMatchPacket message = (SetMatchPacket) setMatchPacketMessage;

        // Retrieve the game session from the incoming message
        GameSession gameSession = message.getGameSession();

        // Only usable in the CONFIG state
        if (gameSession.getCurrentMatch().getMatchState() != MatchState.CONFIG) {
            return ProcessError.wrongStateMessage(message);
        }

        // Authorize: proctorless modes have no proctor, so the game owner sets the packet;
        // otherwise only the proctor may.
        if (gameSession.getGameSettings().isProctorless()) {
            if (!authorizationPolicy.isSessionOwner(gameSession, message.getOriginatingPlayerId())) {
                return ProcessError.accessDeniedMessage(message);
            }
        } else if (gameSession.getProctor() == null ||
                !gameSession.getProctor().getPlayerId().equals(message.getOriginatingPlayerId())) {
            return ProcessError.accessDeniedMessage(message);
        }

        // Retrieve the packet using the packet ID from the message. This runs on
        // the Kafka listener thread, so every failure becomes an error for the
        // sender; nothing may escape (a thrown exception would be redelivered).
        Packet packet;
        try {
            packet = packetClient.getPacketById(message.getPacketId()).block();
        } catch (PacketNotFoundException e) {
            packet = null;
        } catch (QuestionsUnavailableException e) {
            log.warn("SetMatchPacket {} for session {}: sockbowl-questions unavailable ({})",
                    message.getPacketId(), gameSession.getId(), e.getMessage());
            return packetServiceUnavailable(message);
        } catch (RuntimeException e) {
            log.warn("SetMatchPacket {} for session {}: packet fetch failed", message.getPacketId(),
                    gameSession.getId(), e);
            return packetServiceUnavailable(message);
        }

        // If the packet is not found, return an error message
        if (packet == null) {
            return ProcessError.coded(message, PACKET_NOT_FOUND,
                    "Packet id " + message.getPacketId() + " does not exist");
        }

        // Visibility (D2, D15): a draft may only be loaded by its owner or a
        // packet:manage-any holder. The sender's identity comes from the STOMP
        // principal (stampOrigin), never from the client's message body.
        if (!authorizationPolicy.canUsePacketForMatch(packet, message.getOriginatingKeycloakId(),
                message.getOriginatingAuthorities())) {
            return ProcessError.coded(message, PACKET_NOT_AVAILABLE,
                    "Packet id " + message.getPacketId() + " is not available for play");
        }

        // PB-13/D7: null tossup lists are treated as empty, and an empty packet
        // can't be played (there's nothing for AdvanceRound to advance through).
        // Null elements are skipped (M3V1-G-03), so a list of only nulls is empty too.
        List<ContainsTossup> incomingTossups = sortedByOrder(packet.getTossups(), ContainsTossup::getOrder);
        if (incomingTossups.isEmpty()) {
            return ProcessError.coded(message, PACKET_EMPTY, "Packet has no tossups");
        }

        // An EPHEMERAL packet belongs to the one game that first loaded it
        // (R3-G-01): loading it in a second game would hand that game's proctor
        // every answer of a packet only the game service can read.
        if (authorizationPolicy.isAuthEnabled() && packet.getVisibility() == PacketVisibility.EPHEMERAL) {
            String packetId = packet.getId() != null ? packet.getId() : message.getPacketId();
            boolean bound;
            try {
                bound = ephemeralPacketBindings.bindToGame(packetId, gameSession.getId());
            } catch (RuntimeException e) {
                log.warn("SetMatchPacket {} for session {}: could not bind the EPHEMERAL packet",
                        packetId, gameSession.getId(), e);
                return packetServiceUnavailable(message);
            }
            if (!bound) {
                return ProcessError.coded(message, PACKET_NOT_AVAILABLE,
                        "Packet id " + message.getPacketId() + " is not available for play");
            }
        }

        // The author's Keycloak subject was only needed for the visibility check
        // above; it is identity, so it is never stored in or sent with the
        // session (G2-02, AUTH-10).
        packet.setOwnerId(null);
        packet.setOwnerDisplayName(null);

        // Sort the tossups by number, into a fresh mutable list: the incoming
        // list may be immutable (e.g. built with List.of/List.copyOf), so a
        // shuffled wire order never determines play order regardless of how
        // the packet was assembled.
        packet.setTossups(incomingTossups);

        // Sort bonuses by order, then each bonus's parts by order, dropping any
        // bonus left with 0 parts (D7: bonuses pair with tossups by index, so a
        // dropped bonus mid-packet is logged as a data-quality warning). A null
        // bonus list means "no bonuses", not an error.
        List<ContainsBonus> sortedBonuses = sortedByOrder(packet.getBonuses(), ContainsBonus::getOrder);

        List<ContainsBonus> playableBonuses = new ArrayList<>(sortedBonuses.size());
        for (ContainsBonus containsBonus : sortedBonuses) {
            Bonus bonus = containsBonus.getBonus();
            List<HasBonusPart> parts = bonus == null
                    ? List.of() : sortedByOrder(bonus.getBonusParts(), HasBonusPart::getOrder);
            if (parts.isEmpty()) {
                log.warn("SetMatchPacket {} for session {}: dropping bonus {} with no parts",
                        message.getPacketId(), gameSession.getId(), bonus == null ? null : bonus.getId());
                continue;
            }
            bonus.setBonusParts(parts);
            playableBonuses.add(containsBonus);
        }
        packet.setBonuses(playableBonuses);

        // Set the packet for the current match in the game session
        gameSession.getCurrentMatch().setPacket(packet);

        // The proctor who loaded it can read every answer from now on (R3-G2-03).
        // A proctorless owner gets the player view, so reads none.
        if (!gameSession.getGameSettings().isProctorless()) {
            gameSession.recordPacketProctor(gameSession.loadedPacketId(), message.getOriginatingPlayerId());
        }

        return packetUpdateFor(packet, message.getOriginatingPlayerId(), gameSession);
    }

    /**
     * The MatchPacketUpdate for a newly loaded packet, per recipient
     * (R3-G-01): the sender (the proctor, or the owner in a proctorless mode)
     * gets the full update; every other player gets the name and counts but
     * no packet id, which would let them load the packet as proctor of
     * another game and read its answers there.
     */
    static SockbowlOutMessage packetUpdateFor(Packet packet, String loaderId, GameSession gameSession) {
        MatchPacketUpdate full = MatchPacketUpdate.builder()
                .packetId(packet.getId())
                .packetName(packet.getName())
                .tossupCount(tossupCount(packet))
                .bonusCount(bonusCount(packet))
                .recipient(loaderId)
                .build();
        List<String> others = gameSession.getPlayerList().stream()
                .map(Player::getPlayerId)
                .filter(Objects::nonNull)
                .filter(id -> !id.equals(loaderId))
                .toList();
        // An empty recipient list is a broadcast, so the id-free update is
        // only added when someone else is present.
        if (others.isEmpty()) {
            return full;
        }
        return SockbowlMultiOutMessage.builder()
                .sockbowlOutMessage(full)
                .sockbowlOutMessage(publicPacketUpdate(packet, others))
                .build();
    }

    private static ProcessError packetServiceUnavailable(SetMatchPacket message) {
        return ProcessError.coded(message, PACKET_SERVICE_UNAVAILABLE,
                "The packet service is unavailable right now. Try again in a moment.");
    }

    /**
     * Sets the target player as the proctor for the current match in the game session.
     * <p>
     * This method validates the access level of the originating player to ensure they have the
     * necessary permissions to set the proctor for the match. The originating player only has
     * permission to do this if they are the owner of the game OR the originating player is the
     * same as the target player and there is currently no proctor set. If access is denied, an
     * error message is returned. If the target player with the provided ID doesn't exist, an error
     * message is also returned.
     * <p>
     * If everything is valid, the proctor is set for the current match and any other player set
     * as proctor is unset. If the proctor is also part of a team, they are removed from the team.
     * A success message is returned.
     * <p>
     * Proctorless modes reject every SetProctor. Once the match has started only the owner may
     * reassign the proctor (typically to replace one who left; see
     * {@link GameAuthorizationPolicy#canManageProctor}); a self-claim then is a wrong-state error.
     * <p>
     * In CONFIG, a SetProctor that seats a different player (or fills an empty seat) clears the
     * loaded packet and broadcasts a {@link MatchPacketUpdate} with no packet (G2-03).
     *
     * @param setProctor The incoming message that contains the necessary information
     *                   to set a player as proctor.
     * @return SockbowlOutMessage which might be an error message or a success message.
     */
    public SockbowlOutMessage setPlayerAsProctor(SockbowlInMessage setProctor) {
        // Cast the incoming message to the specific type
        SetProctor message = (SetProctor) setProctor;

        // Retrieve the game session from the incoming message
        GameSession gameSession = message.getGameSession();

        // Proctorless modes have no proctor role: a proctor there would get the
        // proctor view (every answer) in a game nobody proctors (G-01).
        if (gameSession.getGameSettings().isProctorless()) {
            return ProcessError.accessDeniedMessage(message);
        }

        // Check if the player making the request is allowed to manage the proctor:
        // the session owner, or (in CONFIG only) a player claiming the role for
        // themselves while none is set. See GameAuthorizationPolicy#canManageProctor.
        if (!authorizationPolicy.canManageProctor(gameSession, message.getOriginatingPlayerId(), message.getTargetPlayer())) {
            // A self-claim after the match has started is a state error; anything else is access denied.
            if (gameSession.getCurrentMatch().getMatchState() != MatchState.CONFIG) {
                return ProcessError.wrongStateMessage(message);
            }
            return ProcessError.accessDeniedMessage(message);
        }

        // Retrieve the target player using the player ID from the message
        Player targetPlayer = gameSession.getPlayerById(message.getTargetPlayer());

        // If the target player is not found, return an error message
        if (targetPlayer == null) {
            return ProcessError.builder().recipient(message.getOriginatingPlayerId()).error("Player id " + message.getTargetPlayer() + " does not exist").build();
        }

        // The seat changes hands (or is filled while empty) in CONFIG: the packet
        // the previous proctor loaded, or one left over from a proctorless mode,
        // must not reach the new proctor, who would read every answer and could
        // then return to a team (G2-03). The new proctor loads it again. After
        // CONFIG the owner is replacing a proctor mid-match, and the match needs
        // its packet, so it stays.
        Player previousProctor = gameSession.getProctor();
        boolean seatChangesHands = previousProctor == null
                || !previousProctor.getPlayerId().equals(targetPlayer.getPlayerId());
        boolean packetCleared = seatChangesHands && clearPacketOnProctorChange(gameSession);

        // If there is currently a proctor set, unset the proctor
        if (previousProctor != null) {
            previousProctor.setPlayerMode(PlayerMode.SPECTATOR);
        }

        // Set the target player as proctor for the current match in the game session
        targetPlayer.setPlayerMode(PlayerMode.PROCTOR);

        // A packet still loaded (the owner replacing a proctor mid-match, or
        // the proctor re-claiming their own seat) is now readable by them
        // (R3-G2-03).
        gameSession.recordPacketProctor(gameSession.loadedPacketId(), targetPlayer.getPlayerId());

        // Remove the proctor from any team they might be a part of
        Team team = gameSession.getTeamByPlayerId(targetPlayer.getPlayerId());
        if (team != null) {
            team.removePlayerFromTeam(targetPlayer.getPlayerId());
        }

        // Return a PlayerRosterUpdate, plus the packet reset if the seat change cleared it
        return withPacketReset(PlayerRosterUpdate.fromGameSession(gameSession), packetCleared);
    }


    /**
     * Sends an updated game state to a player.
     * <p>
     * This method retrieves the current game session from the incoming message, determines the player mode
     * of the player who sent the message, and then sanitizes the game session based on that player mode.
     * A sanitized game session update is then sent back to the originating player. A non-proctor
     * with a packet loaded also gets an id-free {@link MatchPacketUpdate} (name and counts only),
     * because their sanitized view carries no questions to count (R4-NG-02).
     *
     * @param sockbowlInMessage The incoming message containing the player's ID and the current game session.
     *                          This is used to determine the player's mode and retrieve the relevant game session.
     * @return SockbowlOutMessage A message containing the sanitized game session, tailored for the player who
     * sent the request. This message is to be sent back to the originating player.
     */
    private SockbowlOutMessage sendGameState(SockbowlInMessage sockbowlInMessage) {

        // Retrieve the current game session from message
        GameSession gameSession = sockbowlInMessage.getGameSession();

        // Get player that sent the message and determine their player mode
        PlayerMode playerMode = gameSession.getPlayerModeById(sockbowlInMessage.getOriginatingPlayerId());

        // Sanitize and return the session
        String recipient = sockbowlInMessage.getOriginatingPlayerId();
        GameSession gameSessionSanitized = GameSanitizer.sanitizeGameSession(gameSession, playerMode);
        GameSessionUpdate update = GameSessionUpdate.builder().gameSession(gameSessionSanitized).recipient(recipient).build();

        // A non-proctor's view carries no tossups or bonuses (and no packet
        // id), so a player who joins or reloads after the packet was loaded
        // would never learn its counts (R4-NG-02). They also get the id-free
        // MatchPacketUpdate the load sent everyone else, after the session so
        // it applies to the match it describes. The proctor's view already
        // holds the whole packet.
        Packet loaded = gameSession.loadedPacketId() == null ? null : gameSession.getCurrentMatch().getPacket();
        if (loaded == null || playerMode == PlayerMode.PROCTOR) {
            return update;
        }
        return SockbowlMultiOutMessage.builder()
                .sockbowlOutMessage(update)
                .sockbowlOutMessage(publicPacketUpdate(loaded, List.of(recipient)))
                .build();
    }

    /**
     * The id-free MatchPacketUpdate every non-proctor receives for a loaded
     * packet (R3-G-01): name and counts only, never the id or any question.
     */
    private static MatchPacketUpdate publicPacketUpdate(Packet packet, List<String> recipients) {
        return MatchPacketUpdate.builder()
                .packetId(null)
                .packetName(packet.getName())
                .tossupCount(tossupCount(packet))
                .bonusCount(bonusCount(packet))
                .recipients(recipients)
                .build();
    }

    private static int tossupCount(Packet packet) {
        return packet.getTossups() == null ? 0 : packet.getTossups().size();
    }

    private static int bonusCount(Packet packet) {
        return packet.getBonuses() == null ? 0 : packet.getBonuses().size();
    }

    /**
     * Updates game settings including bonuses enabled flag and timer settings.
     * Only the proctor can update settings.
     *
     * @param updateGameSettingsMsg Message containing new settings
     * @return GameSessionUpdate or ProcessError
     */
    public SockbowlOutMessage updateGameSettings(SockbowlInMessage updateGameSettingsMsg) {
        UpdateGameSettings updateGameSettings = (UpdateGameSettings) updateGameSettingsMsg;
        GameSession gameSession = updateGameSettingsMsg.getGameSession();

        // Only usable in the CONFIG state — the same gate the sibling config ops (team
        // changes, packet selection) enforce. Swapping the whole GameSettings blob
        // (game mode, bonuses, timers) mid-match would desync the active state machine.
        if (gameSession.getCurrentMatch().getMatchState() != MatchState.CONFIG) {
            return ProcessError.wrongStateMessage(updateGameSettingsMsg);
        }

        // Authorize: proctorless modes have no proctor, so the game owner updates settings;
        // otherwise only the proctor may.
        if (gameSession.getGameSettings().isProctorless()) {
            if (!authorizationPolicy.isSessionOwner(gameSession, updateGameSettingsMsg.getOriginatingPlayerId())) {
                return ProcessError.accessDeniedMessage(updateGameSettingsMsg);
            }
        } else if (gameSession.getPlayerModeById(updateGameSettingsMsg.getOriginatingPlayerId()) != PlayerMode.PROCTOR) {
            return ProcessError.accessDeniedMessage(updateGameSettingsMsg);
        }

        boolean wasProctorless = gameSession.getGameSettings().isProctorless();

        // Update settings
        gameSession.setGameSettings(updateGameSettings.getGameSettings());

        // Switching between a proctored and a proctorless mode changes who the
        // loaded packet is for (a proctor, or the owner of a game nobody
        // proctors), so it is cleared and loaded again (G2-03).
        boolean isProctorless = gameSession.getGameSettings() != null && gameSession.getGameSettings().isProctorless();
        if (wasProctorless != isProctorless) {
            clearPacketOnProctorChange(gameSession);
        }

        // Switching to a proctorless mode ends the proctor role: a proctor left
        // seated would keep receiving the proctor view (every answer) in a game
        // nobody proctors (G-01). They become a spectator and may pick a team.
        if (gameSession.getGameSettings() != null && gameSession.getGameSettings().isProctorless()) {
            Player leftoverProctor = gameSession.getProctor();
            if (leftoverProctor != null) {
                leftoverProctor.setPlayerMode(PlayerMode.SPECTATOR);
            }
        }

        // Return a sanitized game session update. The raw session carries every
        // player's secret and identity (AUTH-10) and, once a packet is set, its
        // answers, so it must never be broadcast as-is.
        return GameSessionUpdate.sanitizedForEachRecipient(gameSession);
    }

    /**
     * Clears the loaded packet when the proctor seat changes in CONFIG (G2-03).
     * The packet was loaded by, and checked against, the proctor who held the
     * seat (or the owner in a proctorless mode); nobody else may read it
     * through the proctor view, so the next proctor has to load it again.
     * A no-op outside CONFIG and when no packet is loaded.
     *
     * @return true if a loaded packet was cleared
     */
    private static boolean clearPacketOnProctorChange(GameSession gameSession) {
        Match match = gameSession.getCurrentMatch();
        if (match == null || match.getMatchState() != MatchState.CONFIG) {
            return false;
        }
        Packet packet = match.getPacket();
        boolean loaded = packet != null && packet.getId() != null && !packet.getId().isBlank();
        match.setPacket(new Packet());
        return loaded;
    }

    /**
     * {@code update}, followed by a broadcast {@link MatchPacketUpdate} with no
     * packet when {@code packetCleared}, so every client drops the selection.
     */
    private static SockbowlOutMessage withPacketReset(SockbowlOutMessage update, boolean packetCleared) {
        if (!packetCleared) {
            return update;
        }
        return SockbowlMultiOutMessage.builder()
                .sockbowlOutMessage(update)
                .sockbowlOutMessage(MatchPacketUpdate.builder().packetId(null).packetName(null).tossupCount(0).bonusCount(0).build())
                .build();
    }

    /**
     * A new mutable list of the non-null elements of {@code items}, sorted by
     * their {@code order}. An element whose order is null sorts by its list
     * position instead, the same rule as {@code GameSanitizer.readingKey}
     * (M3V1-G-03); the sort is stable, so equal keys keep their wire order.
     * A null list gives an empty list.
     */
    static <T> List<T> sortedByOrder(List<T> items, java.util.function.Function<T, Integer> order) {
        if (items == null) {
            return new ArrayList<>();
        }
        List<int[]> keyed = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            T item = items.get(i);
            if (item != null) {
                Integer o = order.apply(item);
                keyed.add(new int[]{o != null ? o : i, i});
            }
        }
        keyed.sort(Comparator.comparingInt(k -> k[0]));
        List<T> sorted = new ArrayList<>(keyed.size());
        for (int[] k : keyed) {
            sorted.add(items.get(k[1]));
        }
        return sorted;
    }
}
