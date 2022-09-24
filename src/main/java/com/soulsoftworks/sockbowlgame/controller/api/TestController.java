package com.soulsoftworks.sockbowlgame.controller.api;

import com.soulsoftworks.sockbowlgame.service.GameSessionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("/api/v1/")
public class TestController {

    private final GameSessionService gameSessionService;

    public TestController(GameSessionService gameSessionService) {
        this.gameSessionService = gameSessionService;
    }

    @GetMapping("test")
    public void test(){
        //gameSessionService.createNewGame();
    }

}
