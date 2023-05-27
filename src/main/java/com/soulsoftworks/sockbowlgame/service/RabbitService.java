package com.soulsoftworks.sockbowlgame.service;

import com.soulsoftworks.sockbowlgame.config.RabbitMQConfig;
import com.soulsoftworks.sockbowlgame.model.game.socket.SockbowlInMessage;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
public class RabbitService {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitMQConfig rabbitMQConfig;

    public RabbitService(RabbitTemplate rabbitTemplate, RabbitMQConfig rabbitMQConfig) {
        this.rabbitTemplate = rabbitTemplate;
        this.rabbitMQConfig = rabbitMQConfig;
    }

    public void enqueueMessage(SockbowlInMessage message){
        rabbitTemplate.convertAndSend(rabbitMQConfig.GAME_QUEUE, message);
    }
}
