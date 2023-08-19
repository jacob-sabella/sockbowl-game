package com.soulsoftworks.sockbowlgame.controller.websocket;

import com.soulsoftworks.sockbowlgame.model.socket.in.config.SetMatchPacket;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.SetProctor;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.UpdatePlayerTeam;
import com.soulsoftworks.sockbowlgame.model.request.GameSessionInjection;
import com.soulsoftworks.sockbowlgame.service.MessageService;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

@Controller
@MessageMapping("game/config")
public class GameConfigurationController {

    private final MessageService messageService;

    public GameConfigurationController(MessageService messageService) {
        this.messageService = messageService;
    }


    @MessageMapping("/update-player-team")
    public void updatePlayerTeam(GameSessionInjection gameSessionInjection, String playerId, String teamId) {

        // Create message
        UpdatePlayerTeam updatePlayerTeam = UpdatePlayerTeam.builder()
                .originatingPlayerId(gameSessionInjection.getPlayerIdentifiers().getSimpSessionId())
                .gameSessionId(gameSessionInjection.getGameSessionId())
                .targetPlayer(playerId).targetTeam(teamId)
                .build();

        messageService.sendMessage(updatePlayerTeam);
    }

    @MessageMapping("/set-match-packet")
    public void setMatchPacket(GameSessionInjection gameSessionInjection, long packetId) {
        // Create message
        SetMatchPacket setMatchPacket = SetMatchPacket.builder()
                        .originatingPlayerId(gameSessionInjection.getPlayerIdentifiers().getSimpSessionId())
                        .gameSessionId(gameSessionInjection.getGameSessionId())
                        .packetId(packetId)
                        .build();

        messageService.sendMessage(setMatchPacket);
    }


    @MessageMapping("/set-proctor")
    public void setProctor(GameSessionInjection gameSessionInjection, String targetPlayerId) {
        // Create message
        SetProctor setProctor = SetProctor.builder()
                .originatingPlayerId(gameSessionInjection.getPlayerIdentifiers().getSimpSessionId())
                .gameSessionId(gameSessionInjection.getGameSessionId())
                .targetPlayer(targetPlayerId)
                .build();

        messageService.sendMessage(setProctor);
    }

}
