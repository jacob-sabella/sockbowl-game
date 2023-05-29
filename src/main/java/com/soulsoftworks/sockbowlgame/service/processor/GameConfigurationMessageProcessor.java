package com.soulsoftworks.sockbowlgame.service.processor;

import com.soulsoftworks.sockbowlgame.model.game.socket.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.game.socket.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.game.socket.in.config.UpdatePlayerTeamMessage;
import com.soulsoftworks.sockbowlgame.model.game.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.game.state.Player;
import com.soulsoftworks.sockbowlgame.model.game.state.Team;
import com.soulsoftworks.sockbowlgame.service.GameSessionService;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class GameConfigurationMessageProcessor extends GameMessageProcessor {

    private final GameSessionService gameSessionService;

    public GameConfigurationMessageProcessor(GameSessionService gameSessionService) {
        this.gameSessionService = gameSessionService;
    }

    @Override
    protected void initializeProcessorMapping() {
        processorMapping.registerProcessor(UpdatePlayerTeamMessage.class, this::changeTeamForTargetPlayer);
    }

    public SockbowlOutMessage changeTeamForTargetPlayer(SockbowlInMessage updatePlayerTeamMessage) {
        UpdatePlayerTeamMessage message = (UpdatePlayerTeamMessage) updatePlayerTeamMessage;

        GameSession gameSession = message.getGameSession();

        if (!canAskingPlayerChangeTeamForTargetPlayer(gameSession, updatePlayerTeamMessage.getOriginatingPlayerId(),
                message.getTargetPlayer())) {
            //TODO: Return process error here
            return null;
        }

        Team targetTeam = null;
        Team currentTeam = null;
        Player targetPlayer = null;

        // Fetch the full player object from playerList
        for (Player player : gameSession.getPlayerList()) {
            if (player.getPlayerId().equals(message.getTargetPlayer())) {
                targetPlayer = player;
                break;
            }
        }

        // Find the current team of the player and the target team
        for (Team team : gameSession.getTeams()) {
            for (Player player : team.getTeamPlayers()) {
                if(player.getPlayerId().equals(targetPlayer.getPlayerId())) {
                    currentTeam = team;
                }
            }
            if (team.getTeamId().equals(message.getTargetTeam())) {
                targetTeam = team;
            }
        }

        if (targetTeam == null || targetPlayer == null) {
            //TODO: Error - Target team or player not found
            return null;
        }

        // If the player is already on the target team, return error
        if (currentTeam != null && currentTeam.getTeamId().equals(targetTeam.getTeamId())) {
            //TODO: Error - Player is already on the target team
            return null;
        }

        // Remove player from the current team, if they are currently in a team
        if (currentTeam != null) {
            currentTeam.getTeamPlayers().remove(targetPlayer);
        }

        // Add player to the target team
        targetTeam.getTeamPlayers().add(targetPlayer);

        // TODO: Return success message
        return null;
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
