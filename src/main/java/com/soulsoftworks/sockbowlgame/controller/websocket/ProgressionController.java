package com.soulsoftworks.sockbowlgame.controller.websocket;

import com.soulsoftworks.sockbowlgame.model.request.GameSessionInjection;
import com.soulsoftworks.sockbowlgame.model.socket.in.progression.EndMatch;
import com.soulsoftworks.sockbowlgame.model.socket.in.progression.StartMatch;
import com.soulsoftworks.sockbowlgame.service.MessageService;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

@Controller
@MessageMapping("game/progression")
public class ProgressionController {

    private final MessageService messageService;

    public ProgressionController(MessageService messageService) {
        this.messageService = messageService;
    }

    @MessageMapping("/start-match")
    public void startMatch(GameSessionInjection gameSessionInjection) {

        StartMatch startMatch = StartMatch
                .builder()
                .originatingPlayerId(gameSessionInjection.getPlayerIdentifiers().getSimpSessionId())
                .gameSessionId(gameSessionInjection.getGameSessionId())
                .build();

        messageService.sendMessage(startMatch);
    }

    @MessageMapping("/end-match")
    public void endMatch(GameSessionInjection gameSessionInjection) {

        EndMatch endMatch = EndMatch
                .builder()
                .originatingPlayerId(gameSessionInjection.getPlayerIdentifiers().getSimpSessionId())
                .gameSessionId(gameSessionInjection.getGameSessionId())
                .build();

        messageService.sendMessage(endMatch);
    }

}
