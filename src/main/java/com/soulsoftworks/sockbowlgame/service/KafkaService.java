package com.soulsoftworks.sockbowlgame.service;

import com.soulsoftworks.sockbowlgame.model.game.socket.SockbowlInMessage;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

@Service
public class KafkaService {

    private final KafkaTemplate<String, SockbowlInMessage> kafkaTemplate;

    @Value("${sockbowl.kafka.topic.game-topic}")
    private String gameTopic;

    public KafkaService(KafkaTemplate<String, SockbowlInMessage> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void sendMessage(SockbowlInMessage message) {
        kafkaTemplate.send(gameTopic, message);
    }
}
