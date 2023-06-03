package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.model.game.socket.in.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.game.socket.in.config.UpdatePlayerTeamMessage;
import com.soulsoftworks.sockbowlgame.model.game.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.game.socket.out.config.PlayerRosterUpdate;
import com.soulsoftworks.sockbowlgame.model.game.socket.out.error.ProcessErrorMessage;
import com.soulsoftworks.sockbowlgame.model.game.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.game.state.Player;
import com.soulsoftworks.sockbowlgame.model.game.state.Team;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class GameConfigurationMessageProcessor extends GameMessageProcessor {

    @Override
    protected void initializeProcessorMapping() {
        processorMapping.registerProcessor(UpdatePlayerTeamMessage.class, this::changeTeamForTargetPlayer);
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
        UpdatePlayerTeamMessage message = (UpdatePlayerTeamMessage) updatePlayerTeamMessage;

        // Retrieving the game session from the incoming message
        GameSession gameSession = message.getGameSession();

        // Get values from the session
        Team targetTeam = gameSession.findTeamWithId(message.getTargetTeam());
        Team currentTeam = gameSession.getTeamByPlayerId(message.getTargetPlayer());
        Player targetPlayer = gameSession.getPlayerById(message.getTargetPlayer());

        // Checking if the target team or target player is not found
        if (targetTeam == null || targetPlayer == null) {
            // If any is not found, returning an error message
            return ProcessErrorMessage.builder().recipient(updatePlayerTeamMessage.getOriginatingPlayerId())
                    .error("Target team or player does not exist").build();
        }

        // Validating if the player who initiated the request is allowed to change the team of the target player
        if (!canAskingPlayerChangeTeamForTargetPlayer(gameSession, updatePlayerTeamMessage.getOriginatingPlayerId(),
                message.getTargetPlayer())) {
            // If not, returning an error message
            return ProcessErrorMessage.builder().error("Player does not have permission to update team")
                    .recipient(updatePlayerTeamMessage.getOriginatingPlayerId()).build();
        }


        // Checking if the player is already in the target team
        if (currentTeam != null && currentTeam.getTeamId().equals(targetTeam.getTeamId())) {
            // If yes, returning an error message
            return ProcessErrorMessage.builder().error("Player already on team")
                    .recipient(updatePlayerTeamMessage.getOriginatingPlayerId()).build();
        }

        // If the player is currently in a team, remove the player from the current team
        if (currentTeam != null) {
            currentTeam.getTeamPlayers().remove(targetPlayer);
        }

        // Add player to the target team
        targetTeam.addPlayerToTeam(targetPlayer);

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
            Optional<Player> player = gameSession.getPlayerList().stream()
                    .filter(p -> p.getPlayerId().equals(askingPlayer))
                    .findFirst();
            return player.map(Player::isGameOwner).orElse(false);
        }
    }
}
