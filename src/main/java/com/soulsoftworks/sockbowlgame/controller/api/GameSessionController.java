package com.soulsoftworks.sockbowlgame.controller.api;

import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.request.CreateGameRequest;
import com.soulsoftworks.sockbowlgame.model.request.JoinGameRequest;
import com.soulsoftworks.sockbowlgame.model.response.GameSessionIdentifiers;
import com.soulsoftworks.sockbowlgame.model.response.JoinGameResponse;
import com.soulsoftworks.sockbowlgame.service.SessionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;


/**
 * Controller for all game related messages
 */
@RestController
@RequestMapping("api/v1/session/")
public class GameSessionController {

    private final SessionService sessionService;

    public GameSessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * Create a new game with the provided settings
     *
     * @param createGameRequest The settings to create the game with
     */
    @PostMapping("/create-new-game-session")
    public GameSessionIdentifiers createNewGame(@RequestBody CreateGameRequest createGameRequest){

        GameSession gameSession = sessionService.createNewGame(createGameRequest);

        return GameSessionIdentifiers.builder()
                .fromGameSession(gameSession)
                .build();
    }

    /**
     * Join a game with a join code
     */
    @PostMapping("/join-game-session-by-code")
    public JoinGameResponse joinGameSessionWithCode(@Valid @RequestBody JoinGameRequest joinGameRequest){
        return sessionService.addPlayerToGameSessionWithJoinCode(joinGameRequest);
    }

}
