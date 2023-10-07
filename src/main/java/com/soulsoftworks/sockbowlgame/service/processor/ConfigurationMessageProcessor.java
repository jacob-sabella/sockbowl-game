package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.client.PacketClient;
import com.soulsoftworks.sockbowlgame.model.packet.Packet;
import com.soulsoftworks.sockbowlgame.model.socket.in.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.GetGameState;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.SetMatchPacket;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.SetProctor;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.UpdatePlayerTeam;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.progression.GameSessionUpdate;
import com.soulsoftworks.sockbowlgame.model.socket.out.config.MatchPacketUpdate;
import com.soulsoftworks.sockbowlgame.model.socket.out.config.PlayerRosterUpdate;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.ProcessError;
import com.soulsoftworks.sockbowlgame.model.state.*;
import org.springframework.stereotype.Service;

@Service
public class ConfigurationMessageProcessor extends MessageProcessor {

    private final PacketClient packetClient;

    public ConfigurationMessageProcessor(PacketClient packetClient) {
        this.packetClient = packetClient;
    }

    @Override
    protected void initializeProcessorMapping() {
        processorMapping.registerProcessor(UpdatePlayerTeam.class, this::changeTeamForTargetPlayer);
        processorMapping.registerProcessor(SetProctor.class, this::setPlayerAsProctor);
        processorMapping.registerProcessor(SetMatchPacket.class, this::setPacketForMatch);
        processorMapping.registerProcessor(GetGameState.class, this::sendGameState);
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
        Team targetTeam = gameSession.findTeamWithId(message.getTargetTeam());
        Team currentTeam = gameSession.getTeamByPlayerId(message.getTargetPlayer());
        Player targetPlayer = gameSession.getPlayerById(message.getTargetPlayer());

        // Only usable in the CONFIG state
        if(gameSession.getCurrentMatch().getMatchState() != MatchState.CONFIG){
            return ProcessError.wrongStateMessage(message);
        }

        // Checking if the target team or target player is not found
        if (targetTeam == null || targetPlayer == null) {
            // If any is not found, returning an error message
            return ProcessError.builder().recipient(updatePlayerTeamMessage.getOriginatingPlayerId())
                    .error("Target team or player does not exist").build();
        }

        // Validating if the player who initiated the request is allowed to change the team of the target player
        if (!canAskingPlayerChangeTeamForTargetPlayer(gameSession, updatePlayerTeamMessage.getOriginatingPlayerId(),
                message.getTargetPlayer())) {
            // If not, returning an error message
            return ProcessError.accessDeniedMessage(message);
        }


        // Checking if the player is already in the target team
        if (currentTeam != null && currentTeam.getTeamId().equals(targetTeam.getTeamId())) {
            // If yes, returning an error message
            return ProcessError.builder().error("Player already on team")
                    .recipient(updatePlayerTeamMessage.getOriginatingPlayerId()).build();
        }

        // If the player is currently in a team, remove the player from the current team
        if (currentTeam != null) {
            currentTeam.getTeamPlayers().remove(targetPlayer);
        }

        // Add player to the target team
        targetTeam.addPlayerToTeam(targetPlayer);

        // Change player PlayerMode to buzzer
        targetPlayer.setPlayerMode(PlayerMode.BUZZER);

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
        if(gameSession.getCurrentMatch().getMatchState() != MatchState.CONFIG){
            return ProcessError.wrongStateMessage(message);
        }

        // Check if the player making the request is the game owner
        if (!gameSession.isPlayerGameOwner(message.getOriginatingPlayerId())) {
            // If not, return an access denied error message
            return ProcessError.accessDeniedMessage(message);
        }

        // Retrieve the packet using the packet ID from the message
        Packet packet = packetClient.getPacketById(message.getPacketId());

        // If the packet is not found, return an error message
        if (packet == null) {
            return ProcessError.builder().recipient(message.getOriginatingPlayerId())
                    .error("Packet id " + message.getPacketId() + " does not exist").build();
        }

        // Set the packet for the current match in the game session
        gameSession.getCurrentMatch().setPacket(packet);

        // Return a MatchPacketUpdate
        return MatchPacketUpdate.builder()
                .packetId(packet.getId())
                .packetName(packet.getName())
                .build();
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

        // Check if the player making the request is the game owner or the target player when no proctor is set
        if (!(gameSession.isPlayerGameOwner(message.getOriginatingPlayerId())
                || (message.getTargetPlayer().equals(message.getOriginatingPlayerId()) && gameSession.getProctor() == null))) {
            // If not, return an access denied error message
            return ProcessError.accessDeniedMessage(message);
        }

        // Retrieve the target player using the player ID from the message
        Player targetPlayer = gameSession.getPlayerById(message.getTargetPlayer());

        // If the target player is not found, return an error message
        if (targetPlayer == null) {
            return ProcessError.builder().recipient(message.getOriginatingPlayerId())
                    .error("Player id " + message.getTargetPlayer() + " does not exist").build();
        }

        // If there is currently a proctor set, unset the proctor
        if(gameSession.getProctor() != null){
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
     * Determines whether the asking player is allowed to change the team for the target player.
     *
     * <p>The method allows for team change under the following conditions:
     * <ul>
     *     <li>If the asking player and the target player are the same, the method returns true.</li>
     *     <li>If the asking player is not the target player, the method checks whether the asking player
     *     is the game owner. If the asking player is the game owner, the method returns true; otherwise, it
     *     returns false.</li>
     * </ul>
     *
     * @param gameSession  the current GameSession containing the list of players
     * @param askingPlayer the id of the player who is asking to change the team
     * @param targetPlayer the id of the player who is team is being changed
     * @return true if the asking player can change the team of the target player, false otherwise
     */
    private boolean canAskingPlayerChangeTeamForTargetPlayer(GameSession gameSession, String askingPlayer, String targetPlayer) {
        if (askingPlayer.equals(targetPlayer)) {
            return true;
        } else {
            return gameSession.isPlayerGameOwner(askingPlayer);
        }
    }


    private SockbowlOutMessage sendGameState(SockbowlInMessage sockbowlInMessage) {

        // Retrieve the current game session from message
        GameSession gameSession = sockbowlInMessage.getGameSession();

        // Get player that sent the message and determine their player mode
        PlayerMode playerMode = gameSession.getPlayerModeById(sockbowlInMessage.getOriginatingPlayerId());

        // Sanitize and return the session
        return GameSessionUpdate.builder()
                .gameSession(GameSessionSanitizer.sanitize(gameSession, playerMode))
                .recipient(sockbowlInMessage.getOriginatingPlayerId())
                .build();

    }
}
