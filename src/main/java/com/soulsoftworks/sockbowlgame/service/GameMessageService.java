package com.soulsoftworks.sockbowlgame.service;

import com.soulsoftworks.sockbowlgame.model.game.socket.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.game.socket.constants.MessageTypes;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class GameMessageService {

    private final SimpMessagingTemplate simpMessagingTemplate;
    private final KafkaTemplate<String, SockbowlInMessage> kafkaTemplate;

    private final GameSessionService gameSessionService;



    public GameMessageService(SimpMessagingTemplate simpMessagingTemplate,
                              KafkaTemplate<String, SockbowlInMessage> kafkaTemplate,
                              GameSessionService gameSessionService) {
        this.simpMessagingTemplate = simpMessagingTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.gameSessionService = gameSessionService;
    }


    @Value("${sockbowl.kafka.topic.game-topic}")
    private String gameTopic;


    public void sendMessage(String gameTopic, SockbowlInMessage message) {
        kafkaTemplate.send(gameTopic, message);
    }

    public void sendMessage(SockbowlInMessage message) {
        sendMessage(gameTopic, message);
    }

    @KafkaListener(topics = "${sockbowl.kafka.topic.game-topic}", groupId = "game-consumers" )
    public void processGameMessage(ConsumerRecord<String, SockbowlInMessage> record) {
        if(record != null){



            directMessageToService(record.value());
        }
    }

    private void directMessageToService(SockbowlInMessage message){
        if(message.getMessageType() == MessageTypes.CONFIG){

        }
    }
}
