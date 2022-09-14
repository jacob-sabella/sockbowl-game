package com.soulsoftworks.sockbowlgame.websocket.controller;

import com.soulsoftworks.sockbowlgame.websocket.model.request.CreateGameRequest;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.stereotype.Controller;

/**
 * Controller for all game related messages
 */
@Controller
public class GameController {

    /**
     * Create a new game with the provided settings
     * @param createGameRequest The settings to create the game with
     */
    @MessageMapping("/game/create-new-game")
    @SendToUser("/topic/game-created")
    public void createNewGame(CreateGameRequest createGameRequest){

    }
}
