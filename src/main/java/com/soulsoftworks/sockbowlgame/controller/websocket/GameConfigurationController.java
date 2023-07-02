package com.soulsoftworks.sockbowlgame.controller.websocket;

import com.soulsoftworks.sockbowlgame.model.socket.in.config.SetMatchPacket;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.UpdatePlayerTeam;
import com.soulsoftworks.sockbowlgame.model.request.GameSessionInjection;
import com.soulsoftworks.sockbowlgame.service.GameMessageService;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

@Controller
@MessageMapping("game/config")
public class GameConfigurationController {

    private final GameMessageService gameMessageService;

    public GameConfigurationController(GameMessageService gameMessageService) {
        this.gameMessageService = gameMessageService;
    }


    @MessageMapping("/update-player-team")
    public void updatePlayerTeam(GameSessionInjection gameSessionInjection, String playerId, String teamId) {

        // Create message
        UpdatePlayerTeam updatePlayerTeam = UpdatePlayerTeam.builder()
                .originatingPlayerId(gameSessionInjection.getPlayerIdentifiers().getSimpSessionId())
                .gameSessionId(gameSessionInjection.getGameSessionId())
                .targetPlayer(playerId).targetTeam(teamId)
                .build();

        gameMessageService.sendMessage(updatePlayerTeam);
    }

    @MessageMapping("/set-match-packet")
    public void updatePlayerTeam(GameSessionInjection gameSessionInjection, long packetId) {
        // Create message
        SetMatchPacket setMatchPacket = SetMatchPacket.builder()
                        .originatingPlayerId(gameSessionInjection.getPlayerIdentifiers().getSimpSessionId())
                        .gameSessionId(gameSessionInjection.getGameSessionId())
                        .packetId(packetId)
                        .build();

        gameMessageService.sendMessage(setMatchPacket);
    }

}
