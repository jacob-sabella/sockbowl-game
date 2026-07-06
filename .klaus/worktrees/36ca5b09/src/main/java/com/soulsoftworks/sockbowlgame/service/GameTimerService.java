package com.soulsoftworks.sockbowlgame.service;

import com.soulsoftworks.sockbowlgame.model.socket.in.game.TimeoutBonusPart;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.TimeoutRound;
import com.soulsoftworks.sockbowlgame.model.socket.out.game.TimerUpdate;
import com.soulsoftworks.sockbowlgame.model.state.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service responsible for managing server-side game timers.
 * Runs a scheduled task every second to process timers for all active game sessions.
 */
@Service
@Slf4j
public class GameTimerService {

    private final SessionService sessionService;
    private final MessageService messageService;
    private final SimpMessagingTemplate messagingTemplate;

    public GameTimerService(SessionService sessionService,
                           MessageService messageService,
                           SimpMessagingTemplate messagingTemplate) {
        this.sessionService = sessionService;
        this.messageService = messageService;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Scheduled task that runs every second to process all active game timers.
     * For each active session with timers, decrements countdown and broadcasts updates.
     */
    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public void processTimers() {
        List<GameSession> activeSessions = sessionService.getAllActiveSessions();

        for (GameSession session : activeSessions) {
            try {
                processSessionTimers(session);
            } catch (Exception e) {
                log.error("Error processing timers for session {}: {}", session.getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Processes timers for a single game session.
     * Handles both tossup and bonus timers.
     *
     * @param session GameSession to process
     */
    private void processSessionTimers(GameSession session) {
        Round currentRound = session.getCurrentRound();
        if (currentRound == null) {
            return;
        }

        TimerSettings timerSettings = session.getGameSettings().getTimerSettings();

        // Process tossup timer
        if (currentRound.isTossupTimerActive() &&
            currentRound.getRoundState() == RoundState.AWAITING_BUZZ) {

            int remaining = currentRound.getRemainingTossupTimerSeconds() - 1;
            currentRound.setRemainingTossupTimerSeconds(remaining);

            broadcastTimerUpdate(session, "TOSSUP", remaining);

            if (remaining <= 0) {
                handleTossupTimerExpiry(session, timerSettings);
            }
        }

        // Process bonus timer
        if (currentRound.isBonusTimerActive() &&
            currentRound.getRoundState() == RoundState.BONUS_AWAITING_ANSWER) {

            int remaining = currentRound.getRemainingBonusTimerSeconds() - 1;
            currentRound.setRemainingBonusTimerSeconds(remaining);

            broadcastTimerUpdate(session, "BONUS", remaining);

            if (remaining <= 0) {
                handleBonusTimerExpiry(session, timerSettings);
            }
        }

        // Save updated session back to Redis
        sessionService.saveGameSession(session);
    }

    /**
     * Handles tossup timer expiry.
     * If auto-timer is enabled, sends automatic timeout message.
     * Otherwise, just clears the timer and waits for manual timeout.
     *
     * @param session GameSession with expired tossup timer
     * @param timerSettings Timer configuration settings
     */
    private void handleTossupTimerExpiry(GameSession session, TimerSettings timerSettings) {
        session.getCurrentRound().clearTossupTimer();

        if (timerSettings.isAutoTimerEnabled()) {
            // Auto-timeout: create TimeoutRound message and send via Kafka
            TimeoutRound timeoutMsg = TimeoutRound.builder()
                    .gameSessionId(session.getId())
                    .originatingPlayerId(session.getProctor().getPlayerId())
                    .isAutoTimeout(true)
                    .build();

            messageService.sendMessage(timeoutMsg);
            log.debug("Auto-timeout triggered for tossup in session {}", session.getId());
        } else {
            log.debug("Tossup timer expired for session {}, awaiting manual timeout", session.getId());
        }
    }

    /**
     * Handles bonus timer expiry.
     * If auto-timer is enabled, sends automatic timeout message.
     * Otherwise, just clears the timer and waits for manual timeout.
     *
     * @param session GameSession with expired bonus timer
     * @param timerSettings Timer configuration settings
     */
    private void handleBonusTimerExpiry(GameSession session, TimerSettings timerSettings) {
        session.getCurrentRound().clearBonusTimer();

        if (timerSettings.isAutoTimerEnabled()) {
            // Auto-timeout: create TimeoutBonusPart message and send via Kafka
            TimeoutBonusPart timeoutMsg = TimeoutBonusPart.builder()
                    .gameSessionId(session.getId())
                    .originatingPlayerId(session.getProctor().getPlayerId())
                    .isAutoTimeout(true)
                    .build();

            messageService.sendMessage(timeoutMsg);
            log.debug("Auto-timeout triggered for bonus in session {}", session.getId());
        } else {
            log.debug("Bonus timer expired for session {}, awaiting manual timeout", session.getId());
        }
    }

    /**
     * Broadcasts a timer update message to all players in the session.
     *
     * @param session GameSession
     * @param timerType "TOSSUP" or "BONUS"
     * @param remainingSeconds Remaining time in seconds
     */
    private void broadcastTimerUpdate(GameSession session, String timerType, int remainingSeconds) {
        TimerUpdate update = TimerUpdate.builder()
                .timerType(timerType)
                .remainingSeconds(remainingSeconds)
                .build();

        String destination = "/queue/event/" + session.getId();
        messagingTemplate.convertAndSend(destination, update);
    }
}
