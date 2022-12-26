package com.soulsoftworks.sockbowlgame.controller.api;

import com.google.gson.Gson;
import com.soulsoftworks.sockbowlgame.model.game.GameSession;
import com.soulsoftworks.sockbowlgame.model.game.JoinStatus;
import com.soulsoftworks.sockbowlgame.model.request.CreateGameRequest;
import com.soulsoftworks.sockbowlgame.model.request.JoinGameRequest;
import com.soulsoftworks.sockbowlgame.model.response.GameSessionIdentifiers;
import com.soulsoftworks.sockbowlgame.model.response.JoinGameResponse;
import com.soulsoftworks.sockbowlgame.service.GameSessionService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.http.HttpSession;

/**
 * Controller for all game related messages
 */
@Controller
@RequestMapping("api/v1/session/")
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
    @PostMapping("/create-new-game-session")
    @ResponseBody
    public GameSessionIdentifiers createNewGame(CreateGameRequest createGameRequest){

        GameSession gameSession = gameSessionService.createNewGame(createGameRequest);

        return GameSessionIdentifiers.builder()
                .fromGameSession(gameSession)
                .build();
    }


    /**
     * Join a game with a join code
     */
    @PostMapping("/join-game-session-by-code")
    @ResponseBody
    public JoinGameResponse joinGameSessionWithCode(HttpSession session, JoinGameRequest joinGameRequest){
        joinGameRequest.setSessionId(session.getId());
        JoinStatus joinStatus = gameSessionService.addPlayerToGameSessionWithJoinCode(joinGameRequest);

        JoinGameResponse joinGameResponse = new JoinGameResponse();
        joinGameResponse.setJoinStatus(joinStatus);

        if(joinStatus == JoinStatus.SUCCESS){
            joinGameResponse.setSessionId(session.getId());
        }

        return joinGameResponse;
    }

}
