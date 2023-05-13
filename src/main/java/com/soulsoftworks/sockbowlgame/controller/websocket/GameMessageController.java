package com.soulsoftworks.sockbowlgame.controller.websocket;

import com.soulsoftworks.sockbowlgame.service.GameMessageService;
import org.springframework.messaging.handler.annotation.Headers;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.util.Map;

@Controller
@MessageMapping("game")
public class GameMessageController {
    private final GameMessageService gameMessageService;
    public GameMessageController(GameMessageService gameMessageService) {
        this.gameMessageService = gameMessageService;
    }
    @MessageMapping("/request-state")
    public void getGameState(@Headers Map<String, Object> headers) throws Exception {
        gameMessageService.sendGameStateToPlayer(headers.get("simpSessionId").toString());
    }

}
