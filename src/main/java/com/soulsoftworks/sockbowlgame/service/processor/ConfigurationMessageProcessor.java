package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.client.PacketClient;
import com.soulsoftworks.sockbowlgame.client.PacketNotFoundException;
import com.soulsoftworks.sockbowlgame.client.QuestionsUnavailableException;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsTossup;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class ConfigurationMessageProcessor extends MessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationMessageProcessor.class);

    /** SetMatchPacket error codes ({@link ProcessError#getCode()}). */
    public static final String PACKET_NOT_FOUND = "PACKET_NOT_FOUND";
    public static final String PACKET_NOT_AVAILABLE = "PACKET_NOT_AVAILABLE";
    public static final String PACKET_SERVICE_UNAVAILABLE = "PACKET_SERVICE_UNAVAILABLE";

    private final PacketClient packetClient;
    private final GameAuthorizationPolicy authorizationPolicy;

    public ConfigurationMessageProcessor(PacketClient packetClient,
                                         GameAuthorizationPolicy authorizationPolicy) {
        this.packetClient = packetClient;
        this.authorizationPolicy = authorizationPolicy;
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

        // Return a PlayerRosterUpdate
        return PlayerRosterUpdate.fromGameSession(gameSession);
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

        // Sort the tossups by number
        packet.getTossups().sort(Comparator.comparingInt(ContainsTossup::getOrder));

        // Set the packet for the current match in the game session
        gameSession.getCurrentMatch().setPacket(packet);

        // Return a MatchPacketUpdate (with the tossup count for "Tossup N of M" progress)
        return MatchPacketUpdate.builder()
                .packetId(packet.getId())
                .packetName(packet.getName())
                .tossupCount(packet.getTossups().size())
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

        // Check if the player making the request is allowed to manage the proctor
        // (session owner, or claiming the role for themselves while none is set)
        if (!authorizationPolicy.canManageProctor(gameSession, message.getOriginatingPlayerId(), message.getTargetPlayer())) {
            // If not, return access denied error message
            return ProcessError.accessDeniedMessage(message);
        }

        // Retrieve the target player using the player ID from the message
        Player targetPlayer = gameSession.getPlayerById(message.getTargetPlayer());

        // If the target player is not found, return an error message
        if (targetPlayer == null) {
            return ProcessError.builder().recipient(message.getOriginatingPlayerId()).error("Player id " + message.getTargetPlayer() + " does not exist").build();
        }

        // If there is currently a proctor set, unset the proctor
        if (gameSession.getProctor() != null) {
            gameSession.getProctor().setPlayerMode(PlayerMode.SPECTATOR);
        }

        // Set the target player as proctor for the current match in the game session
        targetPlayer.setPlayerMode(PlayerMode.PROCTOR);

        // Remove the proctor from any team they might be a part of
        Team team = gameSession.getTeamByPlayerId(targetPlayer.getPlayerId());
        if (team != null) {
            team.removePlayerFromTeam(targetPlayer.getPlayerId());
        }

        // Return a PlayerRosterUpdate
        return PlayerRosterUpdate.fromGameSession(gameSession);
    }


    /**
     * Sends an updated game state to a player.
     * <p>
     * This method retrieves the current game session from the incoming message, determines the player mode
     * of the player who sent the message, and then sanitizes the game session based on that player mode.
     * A sanitized game session update is then sent back to the originating player.
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
        GameSession gameSessionSanitized = GameSanitizer.sanitizeGameSession(gameSession, playerMode);
        return GameSessionUpdate.builder().gameSession(gameSessionSanitized).recipient(sockbowlInMessage.getOriginatingPlayerId()).build();
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

        // Update settings
        gameSession.setGameSettings(updateGameSettings.getGameSettings());

        // Return a sanitized game session update. The raw session carries every
        // player's secret and identity (AUTH-10) and, once a packet is set, its
        // answers, so it must never be broadcast as-is.
        return sanitizedSessionUpdateForAll(gameSession);
    }

    /**
     * A {@link GameSessionUpdate} for every player in the session, each copy
     * sanitized for its recipient: the proctor (if any) gets the proctor view,
     * everyone else the player view. Sent as targeted messages rather than one
     * broadcast so the proctor's view never reaches other players.
     */
    private SockbowlOutMessage sanitizedSessionUpdateForAll(GameSession gameSession) {
        Player proctor = gameSession.getProctor();
        if (proctor == null) {
            // No proctor: everyone gets the same player view, so a broadcast is fine.
            return GameSessionUpdate.builder()
                    .gameSession(GameSanitizer.sanitizeGameSession(gameSession, PlayerMode.SPECTATOR))
                    .build();
        }

        SockbowlMultiOutMessage.SockbowlMultiOutMessageBuilder<?, ?> multi = SockbowlMultiOutMessage.builder()
                .sockbowlOutMessage(GameSessionUpdate.builder()
                        .gameSession(GameSanitizer.sanitizeGameSession(gameSession, PlayerMode.PROCTOR))
                        .recipient(proctor.getPlayerId())
                        .build());

        List<String> others = gameSession.getPlayerList().stream()
                .map(Player::getPlayerId)
                .filter(id -> !id.equals(proctor.getPlayerId()))
                .toList();
        // An empty recipient list means "broadcast", which would send the player
        // view over the proctor's, so only add it when someone else is present.
        if (!others.isEmpty()) {
            multi.sockbowlOutMessage(GameSessionUpdate.builder()
                    .gameSession(GameSanitizer.sanitizeGameSession(gameSession, PlayerMode.SPECTATOR))
                    .recipients(others)
                    .build());
        }
        return multi.build();
    }
}
