package com.soulsoftworks.sockbowlgame.service;

import com.soulsoftworks.sockbowlgame.model.socket.in.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.socket.in.progression.EndMatch;
import com.soulsoftworks.sockbowlgame.model.socket.out.SockbowlOutMessage;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.state.GameSettings;
import com.soulsoftworks.sockbowlgame.quota.QuotaProperties;
import com.soulsoftworks.sockbowlgame.service.processor.ConfigurationMessageProcessor;
import com.soulsoftworks.sockbowlgame.service.processor.GameMessageProcessor;
import com.soulsoftworks.sockbowlgame.service.processor.ProgressionMessageProcessor;
import com.soulsoftworks.sockbowlgame.usage.HostedSessionQuota;
import com.soulsoftworks.sockbowlgame.util.MutableClock;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Duration;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP-G5 acceptance for the hosted-session activity touch throttle (plan
 * m4-limits section 2.3): a session being actively played bumps its quota
 * slot's activity score at most once per
 * {@code sockbowl.quota.session-touch-interval} (default 1m), not once per
 * message, no matter how chatty the match is.
 */
class MessageServiceActivityTouchTest {

    private static final String SESSION_ID = "SESSION-ACTIVITY-TOUCH";
    private static final Instant START = Instant.parse("2026-09-27T12:00:00Z");

    private ConsumerRecord<String, SockbowlInMessage> progressionMessage() {
        SockbowlInMessage message = EndMatch.builder().gameSessionId(SESSION_ID).build();
        return new ConsumerRecord<>("game-topic", 0, 0L, null, message);
    }

    @Test
    void fiftyMessagesInAMinuteGiveOneTouch() {
        GameSession session = GameSession.builder().id(SESSION_ID).joinCode("JOIN")
                .gameSettings(com.soulsoftworks.sockbowlgame.model.state.GameSettings.builder().build())
                .build();
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.getGameSessionById(SESSION_ID)).thenReturn(session);

        ProgressionMessageProcessor progressionProcessor = mock(ProgressionMessageProcessor.class);
        when(progressionProcessor.processMessage(org.mockito.ArgumentMatchers.any()))
                .thenReturn(mock(SockbowlOutMessage.class));

        HostedSessionQuota hostedSessionQuota = mock(HostedSessionQuota.class);
        MutableClock clock = new MutableClock(START);

        MessageService messageService = new MessageService(mock(SimpMessagingTemplate.class),
                mock(KafkaTemplate.class), sessionService,
                mock(ConfigurationMessageProcessor.class), progressionProcessor,
                mock(GameMessageProcessor.class),
                hostedSessionQuota, new QuotaProperties(), clock);

        // 50 messages, a little over a second apart: 50 * 1.1s < the 1m throttle window.
        for (int i = 0; i < 50; i++) {
            messageService.processGameMessage(progressionMessage());
            clock.advance(Duration.ofMillis(1_100));
        }

        verify(hostedSessionQuota, times(1)).touchActivity(SESSION_ID);
        verify(sessionService, times(50)).saveGameSession(session);
    }

    @Test
    void aMessageAfterTheThrottleWindowTouchesAgain() {
        GameSession session = GameSession.builder().id(SESSION_ID).joinCode("JOIN")
                .gameSettings(com.soulsoftworks.sockbowlgame.model.state.GameSettings.builder().build())
                .build();
        SessionService sessionService = mock(SessionService.class);
        when(sessionService.getGameSessionById(SESSION_ID)).thenReturn(session);

        ProgressionMessageProcessor progressionProcessor = mock(ProgressionMessageProcessor.class);
        when(progressionProcessor.processMessage(org.mockito.ArgumentMatchers.any()))
                .thenReturn(mock(SockbowlOutMessage.class));

        HostedSessionQuota hostedSessionQuota = mock(HostedSessionQuota.class);
        MutableClock clock = new MutableClock(START);

        MessageService messageService = new MessageService(mock(SimpMessagingTemplate.class),
                mock(KafkaTemplate.class), sessionService,
                mock(ConfigurationMessageProcessor.class), progressionProcessor,
                mock(GameMessageProcessor.class),
                hostedSessionQuota, new QuotaProperties(), clock);

        messageService.processGameMessage(progressionMessage());
        verify(hostedSessionQuota, times(1)).touchActivity(anyString());

        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        messageService.processGameMessage(progressionMessage());
        verify(hostedSessionQuota, times(2)).touchActivity(SESSION_ID);
    }
}
