package com.soulsoftworks.sockbowlgame.service;

import com.soulsoftworks.sockbowlgame.model.game.socket.SockbowlMessage;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
public class RabbitService {

    private final RabbitTemplate rabbitTemplate;

    public RabbitService(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public SockbowlMessage getNextMessage(){
        return (SockbowlMessage) rabbitTemplate.receiveAndConvert("GAME_QUEUE");
    }

    public void enqueueMessage(SockbowlMessage message){
        rabbitTemplate.convertAndSend("GAME_QUEUE", message);
    }
}
