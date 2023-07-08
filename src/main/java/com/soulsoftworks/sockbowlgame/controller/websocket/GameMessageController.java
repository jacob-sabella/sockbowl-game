package com.soulsoftworks.sockbowlgame.controller.websocket;

import com.soulsoftworks.sockbowlgame.service.MessageService;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

@Controller
@MessageMapping("game")
public class GameMessageController {
    private final MessageService messageService;
    public GameMessageController(MessageService messageService) {
        this.messageService = messageService;
    }

}
