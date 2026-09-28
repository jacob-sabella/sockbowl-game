package com.soulsoftworks.sockbowlgame.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.soulsoftworks.sockbowlgame.model.socket.constants.MessageQueues;
import com.soulsoftworks.sockbowlgame.model.socket.in.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.socket.constants.MessageTypes;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlMultiOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.socket.out.error.ProcessError;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.state.MatchState;
import com.soulsoftworks.sockbowlgame.quota.QuotaProperties;
import com.soulsoftworks.sockbowlgame.service.processor.ConfigurationMessageProcessor;
import com.soulsoftworks.sockbowlgame.service.processor.GameMessageProcessor;
import com.soulsoftworks.sockbowlgame.service.processor.ProgressionMessageProcessor;
import com.soulsoftworks.sockbowlgame.usage.HostedSessionQuota;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

/**
 * This service is responsible for processing game messages.
 * It listens for incoming Kafka messages, directs the message to the appropriate service based on message type,
 * and sends out processed messages to either specific recipients or all connected clients.
 */
@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    /**
     * The Spring {@code @KafkaListener} container id (distinct from the Kafka
     * consumer {@code groupId}, though they happen to share this value) for
     * the game-topic listener below. {@code config.health.KafkaListenerReadinessHealthIndicator}
     * looks the container up by this id via {@code KafkaListenerEndpointRegistry}
     * to report readiness (M2-LIVE-01).
     */
    public static final String LISTENER_ID = "game-consumers";

    private final SimpMessagingTemplate simpMessagingTemplate;
    private final KafkaTemplate<String, SockbowlInMessage> kafkaTemplate;
    private final SessionService sessionService;
    private final ConfigurationMessageProcessor configurationMessageProcessor;
    private final ProgressionMessageProcessor progressionMessageProcessor;
    private final GameMessageProcessor gameMessageProcessor;
    private final HostedSessionQuota hostedSessionQuota;

    /**
     * Local throttle for the hosted-session activity touch (plan m4-limits
     * section 2.3): at most one {@link HostedSessionQuota#touchActivity} call
     * per session per {@code sockbowl.quota.session-touch-interval} (default
     * 1m), so a busy match doesn't cost a Redis round trip per message. Ticks
     * off the same replaceable {@link Clock} as the rest of the limiter, so
     * tests advance it instead of sleeping.
     */
    private final Cache<String, Boolean> activityTouchThrottle;

    /**
     * Constructor for the MessageService.
     *
     * @param simpMessagingTemplate         Used for sending messages to WebSocket clients.
     * @param kafkaTemplate                 Used for sending messages to Kafka topics.
     * @param sessionService                Used for retrieving and updating game sessions.
     * @param configurationMessageProcessor Used for processing configuration type messages.
     * @param progressionMessageProcessor   Used for processing progression type messages.
     * @param gameMessageProcessor          Used for processing game type messages
     * @param hostedSessionQuota            Used to keep an in-play session's hosted-session quota slot fresh
     * @param quotaProperties               Supplies the activity-touch throttle interval
     * @param clock                         Replaceable clock (M4 limiter/quota clock) driving the throttle
     */
    public MessageService(SimpMessagingTemplate simpMessagingTemplate,
                          KafkaTemplate<String, SockbowlInMessage> kafkaTemplate,
                          SessionService sessionService, ConfigurationMessageProcessor configurationMessageProcessor,
                          ProgressionMessageProcessor progressionMessageProcessor, GameMessageProcessor gameMessageProcessor,
                          HostedSessionQuota hostedSessionQuota, QuotaProperties quotaProperties, Clock clock) {
        this.simpMessagingTemplate = simpMessagingTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.sessionService = sessionService;
        this.configurationMessageProcessor = configurationMessageProcessor;
        this.progressionMessageProcessor = progressionMessageProcessor;
        this.gameMessageProcessor = gameMessageProcessor;
        this.hostedSessionQuota = hostedSessionQuota;
        this.activityTouchThrottle = Caffeine.newBuilder()
                .expireAfterWrite(quotaProperties.getSessionTouchInterval())
                .ticker(() -> clock.millis() * 1_000_000L)
                .build();
    }

    @Value("${sockbowl.kafka.topic.game-topic}")
    private String gameTopic;

    /**
     * Sends a message to a specific Kafka topic.
     *
     * @param gameTopic The Kafka topic to send the message to.
     * @param message   The message to be sent.
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
        log.debug("Sending message to Kafka - type: {}, gameSessionId: {}, playerId: {}",
            message.getMessageType(), message.getGameSessionId(), message.getOriginatingPlayerId());
        sendMessage(gameTopic, message);
    }

    /**
     * Processes an incoming game message from a Kafka topic.
     * Directs the message to the appropriate service, saves changes to the game session if any,
     * and sends out the processed message to either specific recipients or all clients connected to the game session.
     *
     * @param record The Kafka consumer record containing the game message.
     */
    @KafkaListener(id = LISTENER_ID, topics = "${sockbowl.kafka.topic.game-topic}", groupId = "game-consumers")
    public void processGameMessage(ConsumerRecord<String, SockbowlInMessage> record) {
        // Load, process, save and broadcast under the session's lock
        // (M2R2-LIVE-01): a REST join or timer tick that saves in between would
        // otherwise be overwritten by this handler's stale copy. Broadcasting
        // inside the lock also keeps clients' updates in save order.
        String gameSessionId = record == null || record.value() == null ? null : record.value().getGameSessionId();
        GameSessionLocks.withLock(gameSessionId, () -> processGameMessageLocked(record));
    }

    private void processGameMessageLocked(ConsumerRecord<String, SockbowlInMessage> record) {
        if (record != null) {
            // Retrieve the game session from the incoming message
            SockbowlInMessage message = record.value();
            log.debug("Received message from Kafka - type: {}, gameSessionId: {}, playerId: {}",
                message.getMessageType(), message.getGameSessionId(), message.getOriginatingPlayerId());

            GameSession gameSession = sessionService.getGameSessionById(message.getGameSessionId());
            if (gameSession == null) {
                log.error("Game session not found: {}", message.getGameSessionId());
                return;
            }
            message.setGameSession(gameSession);

            // Direct the message to the appropriate service for processing
            SockbowlOutMessage sockbowlOutMessage = directMessageToService(message);
            log.debug("Processed message - outgoing type: {}", sockbowlOutMessage.getClass().getSimpleName());

            // If no error occured, update the game session
            if (!(sockbowlOutMessage instanceof ProcessError)) {
                sessionService.saveGameSession(gameSession);
                touchHostedSessionActivity(gameSession.getId());
            }

            List<SockbowlOutMessage> sockbowlOutMessagesToProcess;

            if (sockbowlOutMessage instanceof SockbowlMultiOutMessage) {
                sockbowlOutMessagesToProcess = ((SockbowlMultiOutMessage) sockbowlOutMessage).getSockbowlOutMessages();
            } else {
                sockbowlOutMessagesToProcess = List.of(sockbowlOutMessage);
            }

            // Send out all the sockbowl messages
            for (SockbowlOutMessage singleMessage : sockbowlOutMessagesToProcess) {
                // If there are specified recipients for the outgoing message, send the message to them.
                // Otherwise, send the message to all clients connected to the game session.
                if (!singleMessage.getRecipients().isEmpty()) {
                    log.debug("Sending targeted message to {} recipients for game {}",
                        singleMessage.getRecipients().size(), gameSession.getId());
                    singleMessage.getRecipients().forEach(recipient -> {
                        String destination = "/" + MessageQueues.GAME_EVENT_QUEUE + "/" +
                            gameSession.getId() + "/" + recipient;
                        log.debug("Sending to: {}", destination);
                        simpMessagingTemplate.convertAndSend(destination, singleMessage);
                    });
                } else {
                    String destination = "/" + MessageQueues.GAME_EVENT_QUEUE + "/" + gameSession.getId();
                    log.debug("Broadcasting message to all players at: {}", destination);
                    simpMessagingTemplate.convertAndSend(destination, singleMessage);
                }
            }

        }
    }

    /**
     * Bumps the hosted-session quota's activity score for {@code sessionId} at
     * most once per {@code sockbowl.quota.session-touch-interval} (plan
     * m4-limits section 2.3): a game being actively played never goes idle out
     * of its owner's concurrent quota, without a Redis round trip per message.
     */
    private void touchHostedSessionActivity(String sessionId) {
        if (activityTouchThrottle.getIfPresent(sessionId) != null) {
            return;
        }
        activityTouchThrottle.put(sessionId, Boolean.TRUE);
        hostedSessionQuota.touchActivity(sessionId);
    }

    /**
     * Directs the message to the appropriate service based on the message type.
     *
     * @param message The incoming game message.
     * @return The processed outgoing game message.
     */
    private SockbowlOutMessage directMessageToService(SockbowlInMessage message) {
        if (message.getMessageType() == MessageTypes.CONFIG) {
            /*if (message.getGameSession().getCurrentMatch().getMatchState() != MatchState.CONFIG) {
                return ProcessError.builder().error("Config message received during non-config state")
                        .recipient(message.getOriginatingPlayerId())
                        .build();
            }*/
            return configurationMessageProcessor.processMessage(message);
        } else if (message.getMessageType() == MessageTypes.PROGRESSION) {
            return progressionMessageProcessor.processMessage(message);
        } else if (message.getMessageType() == MessageTypes.GAME) {
            /*if (message.getGameSession().getCurrentMatch().getMatchState() != MatchState.IN_GAME) {
                return ProcessError.builder().error("Game message received during non-config state")
                        .recipient(message.getOriginatingPlayerId())
                        .build();
            }*/
            return gameMessageProcessor.processMessage(message);
        } else {
            return ProcessError.builder().error("Unknown message type")
                    .recipient(message.getOriginatingPlayerId())
                    .build();
        }
    }
}
