package com.soulsoftworks.sockbowlgame.service;

import com.soulsoftworks.sockbowlgame.model.game.socket.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.game.socket.constants.MessageTypes;
import com.soulsoftworks.sockbowlgame.model.game.state.GameSession;
import com.soulsoftworks.sockbowlgame.service.processor.GameConfigurationMessageProcessor;
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
    private final GameConfigurationMessageProcessor gameConfigurationMessageProcessor;



    public GameMessageService(SimpMessagingTemplate simpMessagingTemplate,
                              KafkaTemplate<String, SockbowlInMessage> kafkaTemplate,
                              GameSessionService gameSessionService, GameConfigurationMessageProcessor gameConfigurationMessageProcessor) {
        this.simpMessagingTemplate = simpMessagingTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.gameSessionService = gameSessionService;
        this.gameConfigurationMessageProcessor = gameConfigurationMessageProcessor;
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
            SockbowlInMessage message = record.value();
            GameSession gameSession = gameSessionService.getGameSessionById(message.getGameSessionId());
            message.setGameSession(gameSession);
            directMessageToService(message);
        }
    }

    private void directMessageToService(SockbowlInMessage message){
        if(message.getMessageType() == MessageTypes.CONFIG){
            gameConfigurationMessageProcessor.processMessage(message);
        }
    }
}
