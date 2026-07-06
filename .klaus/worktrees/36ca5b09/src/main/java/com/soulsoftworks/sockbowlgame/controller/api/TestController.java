package com.soulsoftworks.sockbowlgame.controller.api;

import com.soulsoftworks.sockbowlgame.client.PacketClient;
import com.soulsoftworks.sockbowlgame.service.SessionService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/api/v1/")
public class TestController {

    private final PacketClient packetClient;

    public TestController(PacketClient packetClient) {
        this.packetClient = packetClient;
    }

    @GetMapping("test")
    public void test(){
        System.out.println(packetClient.getPacketById("17"));
    }

}
