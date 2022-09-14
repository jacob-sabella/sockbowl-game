package com.soulsoftworks.sockbowlgame.api;

import com.soulsoftworks.sockbowlgame.game.service.SessionManagementService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("/api/v1/")
public class TestController {

    private final SessionManagementService sessionManagementService;

    public TestController(SessionManagementService sessionManagementService) {
        this.sessionManagementService = sessionManagementService;
    }

    @GetMapping("test")
    public void test(){
        sessionManagementService.createNewGame();
    }

}
