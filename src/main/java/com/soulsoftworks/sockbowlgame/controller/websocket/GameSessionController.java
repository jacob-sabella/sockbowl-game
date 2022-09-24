package com.soulsoftworks.sockbowlgame.controller.websocket;

import com.google.gson.Gson;
import com.soulsoftworks.sockbowlgame.model.request.CreateGameRequest;
import com.soulsoftworks.sockbowlgame.model.response.GameSessionIdentifiers;
import com.soulsoftworks.sockbowlgame.model.game.GameSession;
import com.soulsoftworks.sockbowlgame.service.GameSessionService;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

/**
 * Controller for all game related messages
 */
@Controller
public class GameSessionController {

    private final GameSessionService gameSessionService;
    private final Gson gson = new Gson();

    public GameSessionController(GameSessionService gameSessionService) {
        this.gameSessionService = gameSessionService;
    }

    /**
     * Create a new game with the provided settings
     *
     * @param createGameRequest The settings to create the game with
     */
    @MessageMapping("/create-new-game-session")
    @SendToUser("/topic/game-session-created")
    public String createNewGame(CreateGameRequest createGameRequest){

        GameSession gameSession = gameSessionService.createNewGame(createGameRequest);

        return gson.toJson(GameSessionIdentifiers.builder()
                .fromGameSession(gameSession)
                .build());
    }
}
