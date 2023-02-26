package com.soulsoftworks.sockbowlgame.controller.websocket;

import com.google.gson.Gson;
import com.soulsoftworks.sockbowlgame.model.request.JoinGameRequest;
import com.soulsoftworks.sockbowlgame.service.GameSessionService;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

@Controller
public class GameMessageController {

    private final GameSessionService gameSessionService;
    private final Gson gson = new Gson();

    public GameMessageController(GameSessionService gameSessionService) {
        this.gameSessionService = gameSessionService;
    }

    /**
     * Join a game with a join code
     */
    @MessageMapping("/game-session-listen")
    @SendToUser("/topic/game-session-message")
    public String joinGameSessionWithCode(@Header("simpSessionId") String sessionId,
                                          JoinGameRequest joinGameRequest){
        joinGameRequest.setSessionId(sessionId);
        return String.valueOf(gameSessionService.addPlayerToGameSessionWithJoinCode(joinGameRequest));
    }
}
