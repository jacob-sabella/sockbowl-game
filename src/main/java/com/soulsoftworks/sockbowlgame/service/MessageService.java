package com.soulsoftworks.sockbowlgame.service;

import com.soulsoftworks.sockbowlgame.model.socket.constants.MessageQueues;
import com.soulsoftworks.sockbowlgame.model.socket.in.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.socket.constants.MessageTypes;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlMultiOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.ProcessError;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.service.processor.ConfigurationMessageProcessor;
import com.soulsoftworks.sockbowlgame.service.processor.GameMessageProcessor;
import com.soulsoftworks.sockbowlgame.service.processor.ProgressionMessageProcessor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * This service is responsible for processing game messages.
 * It listens for incoming Kafka messages, directs the message to the appropriate service based on message type,
 * and sends out processed messages to either specific recipients or all connected clients.
 */
@Service
public class MessageService {

    private final SimpMessagingTemplate simpMessagingTemplate;
    private final KafkaTemplate<String, SockbowlInMessage> kafkaTemplate;
    private final SessionService sessionService;
    private final ConfigurationMessageProcessor configurationMessageProcessor;
    private final ProgressionMessageProcessor progressionMessageProcessor;
    private final GameMessageProcessor gameMessageProcessor;

    /**
     * Constructor for the MessageService.
     *
     * @param simpMessagingTemplate         Used for sending messages to WebSocket clients.
     * @param kafkaTemplate                 Used for sending messages to Kafka topics.
     * @param sessionService                Used for retrieving and updating game sessions.
     * @param configurationMessageProcessor Used for processing configuration type messages.
     * @param progressionMessageProcessor   Used for processing progression type messages.
     * @param gameMessageProcessor          Used for processing game type messages
     */
    public MessageService(SimpMessagingTemplate simpMessagingTemplate,
                          KafkaTemplate<String, SockbowlInMessage> kafkaTemplate,
                          SessionService sessionService, ConfigurationMessageProcessor configurationMessageProcessor, ProgressionMessageProcessor progressionMessageProcessor, GameMessageProcessor gameMessageProcessor) {
        this.simpMessagingTemplate = simpMessagingTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.sessionService = sessionService;
        this.configurationMessageProcessor = configurationMessageProcessor;
        this.progressionMessageProcessor = progressionMessageProcessor;
        this.gameMessageProcessor = gameMessageProcessor;
    }

    @Value("${sockbowl.kafka.topic.game-topic}")
    private String gameTopic;

    /**
     * Sends a message to a specific Kafka topic.
     *
     * @param gameTopic The Kafka topic to send the message to.
     * @param message The message to be sent.
     */
    public void sendMessage(String gameTopic, SockbowlInMessage message) {
        kafkaTemplate.send(gameTopic, message);
    }

    /**
     * Sends a message to the default Kafka topic.
     *
     * @param message The message to be sent.
     */
    public void sendMessage(SockbowlInMessage message) {
        sendMessage(gameTopic, message);
    }

    /**
     * Processes an incoming game message from a Kafka topic.
     * Directs the message to the appropriate service, saves changes to the game session if any,
     * and sends out the processed message to either specific recipients or all clients connected to the game session.
     *
     * @param record The Kafka consumer record containing the game message.
     */
    @KafkaListener(topics = "${sockbowl.kafka.topic.game-topic}", groupId = "game-consumers" )
    public void processGameMessage(ConsumerRecord<String, SockbowlInMessage> record) {
        if(record != null){
            // Retrieve the game session from the incoming message
            SockbowlInMessage message = record.value();
            GameSession gameSession = sessionService.getGameSessionById(message.getGameSessionId());
            message.setGameSession(gameSession);

            // Direct the message to the appropriate service for processing
            SockbowlOutMessage sockbowlOutMessage = directMessageToService(message);

            // If no error occured, update the game session
            if(!(sockbowlOutMessage instanceof ProcessError)){
                sessionService.saveGameSession(gameSession);
            }

            List<SockbowlOutMessage> sockbowlOutMessagesToProcess;

            if(sockbowlOutMessage instanceof SockbowlMultiOutMessage){
                sockbowlOutMessagesToProcess = ((SockbowlMultiOutMessage) sockbowlOutMessage).getSockbowlOutMessages();
            } else{
                sockbowlOutMessagesToProcess = List.of(sockbowlOutMessage);
            }

            // Send out all the sockbowl messages
            for(SockbowlOutMessage singleMessage : sockbowlOutMessagesToProcess){
                // If there are specified recipients for the outgoing message, send the message to them.
                // Otherwise, send the message to all clients connected to the game session.
                if(singleMessage.getRecipients().size() > 0){
                    singleMessage.getRecipients().forEach(recipient ->
                            simpMessagingTemplate.convertAndSend("/" + MessageQueues.GAME_EVENT_QUEUE + "/" +
                                    gameSession.getId() + "/" + recipient, singleMessage)
                    );
                } else{
                    simpMessagingTemplate.convertAndSend("/" +
                                    MessageQueues.GAME_EVENT_QUEUE + "/" + gameSession.getId(),
                            singleMessage);
                }
            }

        }
    }

    /**
     * Directs the message to the appropriate service based on the message type.
     *
     * @param message The incoming game message.
     * @return The processed outgoing game message.
     */
    private SockbowlOutMessage directMessageToService(SockbowlInMessage message){
        // If the message is a configuration type, direct it to the configuration message processor.
        // Otherwise, create an error message indicating an unknown message type.
        if(message.getMessageType() == MessageTypes.CONFIG){
            return configurationMessageProcessor.processMessage(message);
        } else if (message.getMessageType() == MessageTypes.PROGRESSION){
            return progressionMessageProcessor.processMessage(message);
        } else if (message.getMessageType() == MessageTypes.GAME){
            return gameMessageProcessor.processMessage(message);
        } else {
            return ProcessError.builder().error("Unknown message type")
                    .recipient(message.getOriginatingPlayerId())
                    .build();
        }
    }
}
