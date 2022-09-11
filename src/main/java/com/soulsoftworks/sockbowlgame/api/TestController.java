package com.soulsoftworks.sockbowlgame.api;

import com.soulsoftworks.sockbowlgame.game.service.GameService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("/api/v1/")
public class TestController {

    private final GameService gameService;

    public TestController(GameService gameService) {
        this.gameService = gameService;
    }

    @GetMapping("test")
    public void test(){
        gameService.createNewGame();
    }

}
