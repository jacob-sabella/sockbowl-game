package com.soulsoftworks.sockbowlgame.service;

import com.soulsoftworks.sockbowlgame.model.socket.out.game.ReadingUpdate;
import com.soulsoftworks.sockbowlgame.model.socket.out.game.TimerUpdate;
import com.soulsoftworks.sockbowlgame.model.state.GameMode;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.state.GameSettings;
import com.soulsoftworks.sockbowlgame.model.state.Round;
import com.soulsoftworks.sockbowlgame.model.state.RoundState;
import com.soulsoftworks.sockbowlgame.model.state.TimerSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GameTimerServiceTest {

    private static final String QUESTION_20_WORDS =
            "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty";

    private SessionService sessionService;
    private MessageService messageService;
    private SimpMessagingTemplate messagingTemplate;
    private GameTimerService gameTimerService;

    @BeforeEach
    void setup() {
        sessionService = mock(SessionService.class);
        messageService = mock(MessageService.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        gameTimerService = new GameTimerService(sessionService, messageService, messagingTemplate);
    }

    private GameSession buildSession(GameMode gameMode, RoundState roundState, int revealedWordCount,
                                      int totalWordCount, int readingWordsPerSecond) {
        Round round = new Round();
        round.setRoundState(roundState);
        round.setQuestion(QUESTION_20_WORDS);
        round.setRevealedWordCount(revealedWordCount);
        round.setTotalWordCount(totalWordCount);

        TimerSettings timerSettings = TimerSettings.builder()
                .readingWordsPerSecond(readingWordsPerSecond)
                .tossupTimerSeconds(5)
                .build();

        GameSettings gameSettings = GameSettings.builder()
                .gameMode(gameMode)
                .timerSettings(timerSettings)
                .build();

        GameSession session = GameSession.builder()
                .id("TEST-SESSION")
                .joinCode("ABCD")
                .gameSettings(gameSettings)
                .build();
        session.getCurrentMatch().setCurrentRound(round);

        when(sessionService.getAllActiveSessions()).thenReturn(List.of(session));

        return session;
    }

    @Test
    void revealAdvancesByWordsPerSecondEachTick() {
        GameSession session = buildSession(GameMode.AUTO_PROCTOR, RoundState.PROCTOR_READING, 0, 20, 4);

        gameTimerService.processTimers();

        Round round = session.getCurrentRound();
        assertEquals(4, round.getRevealedWordCount());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(anyString(), captor.capture());
        ReadingUpdate update = (ReadingUpdate) captor.getValue();
        assertEquals(4, update.getRevealedWordCount());
        assertEquals(20, update.getTotalWordCount());
        assertEquals("one two three four", update.getRevealedText());
    }

    @Test
    void revealPausesDuringAwaitingAnswer() {
        GameSession session = buildSession(GameMode.AUTO_PROCTOR, RoundState.AWAITING_ANSWER, 8, 20, 4);

        gameTimerService.processTimers();

        assertEquals(8, session.getCurrentRound().getRevealedWordCount());
        verify(messagingTemplate, times(0)).convertAndSend(anyString(), any(ReadingUpdate.class));
    }

    @Test
    void revealResumesAfterWrongBuzzReturnsToAwaitingBuzzFromAwaitingBuzzState() {
        GameSession session = buildSession(GameMode.AUTO_PROCTOR, RoundState.AWAITING_BUZZ, 8, 20, 4);

        gameTimerService.processTimers();

        assertEquals(12, session.getCurrentRound().getRevealedWordCount());
    }

    @Test
    void revealResumesAfterWrongBuzzReturnsToAwaitingBuzzFromProctorReadingState() {
        GameSession session = buildSession(GameMode.AUTO_PROCTOR, RoundState.PROCTOR_READING, 8, 20, 4);

        gameTimerService.processTimers();

        assertEquals(12, session.getCurrentRound().getRevealedWordCount());
    }

    @Test
    void fullRevealArmsTossupTimer() {
        GameSession session = buildSession(GameMode.AUTO_PROCTOR, RoundState.PROCTOR_READING, 18, 20, 4);

        gameTimerService.processTimers();

        Round round = session.getCurrentRound();
        assertEquals(20, round.getRevealedWordCount());
        assertTrue(round.isTossupTimerActive());
        assertEquals(5, round.getRemainingTossupTimerSeconds());
        assertEquals(RoundState.AWAITING_BUZZ, round.getRoundState());
        assertTrue(round.isProctorFinishedReading());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(anyString(), captor.capture());
        ReadingUpdate update = (ReadingUpdate) captor.getValue();
        assertEquals(20, update.getRevealedWordCount());
    }

    @Test
    void revealDoesNotTickForSinglePlayer() {
        GameSession session = buildSession(GameMode.SINGLE_PLAYER, RoundState.PROCTOR_READING, 0, 20, 4);

        gameTimerService.processTimers();

        assertEquals(0, session.getCurrentRound().getRevealedWordCount());
        verify(messagingTemplate, times(0)).convertAndSend(anyString(), any(ReadingUpdate.class));
    }

    @Test
    void revealDoesNotTickForClassic() {
        GameSession session = buildSession(GameMode.QUIZ_BOWL_CLASSIC, RoundState.PROCTOR_READING, 0, 20, 4);

        gameTimerService.processTimers();

        assertEquals(0, session.getCurrentRound().getRevealedWordCount());
        verify(messagingTemplate, times(0)).convertAndSend(anyString(), any(ReadingUpdate.class));
    }

    @Test
    void revealDoesNotTickPastTotalWordCount() {
        GameSession session = buildSession(GameMode.AUTO_PROCTOR, RoundState.AWAITING_BUZZ, 20, 20, 4);
        session.getCurrentRound().startTossupTimer(5);

        gameTimerService.processTimers();

        assertEquals(20, session.getCurrentRound().getRevealedWordCount());
        verify(messagingTemplate, times(0)).convertAndSend(anyString(), any(ReadingUpdate.class));
        // Pre-existing tossup-timer tick still proceeds normally.
        verify(messagingTemplate, times(1)).convertAndSend(anyString(), any(TimerUpdate.class));
    }
}
