package com.soulsoftworks.sockbowlgame.controller.websocket;

import com.soulsoftworks.sockbowlgame.model.game.socket.in.config.UpdatePlayerTeamMessage;
import com.soulsoftworks.sockbowlgame.model.request.GameSessionInjection;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

@Controller
@MessageMapping("game/config")
public class GameConfigurationController {



    @MessageMapping("/update-player-team")
    public void updatePlayerTeam(GameSessionInjection gameSessionInjection, String playerId, String teamId) {

        // Create message
        UpdatePlayerTeamMessage updatePlayerTeamMessage = UpdatePlayerTeamMessage.builder()
                .originatingPlayerId(gameSessionInjection.getPlayerIdentifiers().getSimpSessionId())
                .gameSessionId(gameSessionInjection.getGameSessionId())
                .targetPlayer(playerId).targetTeam(teamId)
                .build();

        //rabbitService.enqueueMessage(updatePlayerTeamMessage);
    }

}
