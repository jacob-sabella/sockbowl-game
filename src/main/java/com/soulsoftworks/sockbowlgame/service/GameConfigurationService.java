package com.soulsoftworks.sockbowlgame.service;

import com.soulsoftworks.sockbowlgame.model.game.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.game.state.Player;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class GameConfigurationService {

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
     * @param gameSession the current GameSession containing the list of players
     * @param askingPlayer the id of the player who is asking to change the team
     * @param targetPlayer the id of the player who is team is being changed
     * @return true if the asking player can change the team of the target player, false otherwise
     */
    public boolean canAskingPlayerChangeTeamForTargetPlayer(GameSession gameSession, String askingPlayer, String targetPlayer){
        if(askingPlayer.equals(targetPlayer)){
            return true;
        } else{
            Optional<Player> player = gameSession.getPlayerList().stream()
                    .filter(p -> p.getPlayerId().equals(askingPlayer))
                    .findFirst();
            return player.map(Player::isGameOwner).orElse(false);
        }
    }
}
