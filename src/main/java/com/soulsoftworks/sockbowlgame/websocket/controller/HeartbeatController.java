package com.soulsoftworks.sockbowlgame.websocket.controller;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

/**
 * Controller for getting uptime status data from the server
 */
@Controller
public class HeartbeatController {

    @MessageMapping("/heartbeat")
    @SendTo("/topic/heartbeat")
    public String heartbeat() throws Exception {
        return "Hello! I am alive!";
    }
}
