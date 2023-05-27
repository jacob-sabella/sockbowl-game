package com.soulsoftworks.sockbowlgame.controller.websocket;

import com.soulsoftworks.sockbowlgame.model.request.GameSessionInjection;
import com.soulsoftworks.sockbowlgame.service.GameMessageService;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

@Controller
@MessageMapping("game")
public class GameMessageController {
    private final GameMessageService gameMessageService;
    public GameMessageController(GameMessageService gameMessageService) {
        this.gameMessageService = gameMessageService;
    }

}
