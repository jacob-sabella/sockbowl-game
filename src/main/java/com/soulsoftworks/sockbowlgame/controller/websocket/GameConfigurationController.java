package com.soulsoftworks.sockbowlgame.controller.websocket;

import com.soulsoftworks.sockbowlgame.model.socket.in.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.socket.in.config.GetGameState;
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
    public void updatePlayerTeam(GameSessionInjection gameSessionInjection, UpdatePlayerTeam updatePlayerTeam) {

        updatePlayerTeam.setOriginatingPlayerId(gameSessionInjection.getPlayerIdentifiers().getSimpSessionId());
        updatePlayerTeam.setGameSessionId(gameSessionInjection.getGameSessionId());

        messageService.sendMessage(updatePlayerTeam);
    }

    @MessageMapping("/set-match-packet")
    public void setMatchPacket(GameSessionInjection gameSessionInjection, SetMatchPacket setMatchPacket) {

        setMatchPacket.setOriginatingPlayerId(gameSessionInjection.getPlayerIdentifiers().getSimpSessionId());
        setMatchPacket.setGameSessionId(gameSessionInjection.getGameSessionId());

        messageService.sendMessage(setMatchPacket);
    }


    @MessageMapping("/set-proctor")
    public void setProctor(GameSessionInjection gameSessionInjection, SetProctor setProctor) {

        setProctor.setOriginatingPlayerId(gameSessionInjection.getPlayerIdentifiers().getSimpSessionId());
        setProctor.setGameSessionId(gameSessionInjection.getGameSessionId());

        messageService.sendMessage(setProctor);
    }


    @MessageMapping("/get-game")
    public void getGameSession(GameSessionInjection gameSessionInjection) {

        GetGameState getGameState = GetGameState.builder()
                .originatingPlayerId(gameSessionInjection.getPlayerIdentifiers().getSimpSessionId())
                .gameSessionId(gameSessionInjection.getGameSessionId())
                .build();

        messageService.sendMessage(getGameState);
    }

}
