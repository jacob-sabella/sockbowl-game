package com.soulsoftworks.sockbowlgame.service;

import com.soulsoftworks.sockbowlgame.model.socket.in.game.AdvanceRound;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.StartBonus;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.SubmitAnswer;
import com.soulsoftworks.sockbowlgame.model.socket.out.game.ReadingUpdate;
import com.soulsoftworks.sockbowlgame.util.QuestionTokenizer;
import com.soulsoftworks.sockbowlgame.model.socket.out.game.TimerUpdate;
import com.soulsoftworks.sockbowlgame.model.state.Buzz;
import com.soulsoftworks.sockbowlgame.model.state.GameMode;
import com.soulsoftworks.sockbowlgame.model.state.Player;
import com.soulsoftworks.sockbowlgame.model.state.PlayerMode;
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
import static org.mockito.Mockito.never;
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
        // The tick re-reads each session under its lock (M2R2-LIVE-01).
        when(sessionService.getGameSessionById(session.getId())).thenReturn(session);

        return session;
    }

    @Test
    void revealSpendsOneSecondOfReadingPerTick() {
        // 4 words/s is 250ms for an average word; these short words cost 187-203ms each,
        // so one second buys five of them, and the ~48ms left carries into the next tick.
        GameSession session = buildSession(GameMode.AUTO_PROCTOR, RoundState.PROCTOR_READING, 0, 20, 4);

        gameTimerService.processTimers();

        Round round = session.getCurrentRound();
        assertEquals(5, round.getRevealedWordCount());
        assertEquals(47, round.getReadingCarryMillis());

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(anyString(), captor.capture());
        ReadingUpdate update = (ReadingUpdate) captor.getValue();
        assertEquals(5, update.getRevealedWordCount());
        assertEquals(20, update.getTotalWordCount());
        assertEquals("one two three four five", update.getRevealedText());
    }

    @Test
    void sentenceEndsAndLongWordsTakeLongerToRead() {
        GameSession session = buildSession(GameMode.AUTO_PROCTOR, RoundState.PROCTOR_READING, 0, 6, 4);
        session.getCurrentRound().setQuestion("Extraordinarily, the <b>cat</b> sat. Then slept.");

        gameTimerService.processTimers();
        // "Extraordinarily," 1.35+0.5 → 462ms; "the" 187; "cat" 187; "sat." 437 doesn't fit.
        assertEquals(3, session.getCurrentRound().getRevealedWordCount());

        gameTimerService.processTimers();
        assertEquals(6, session.getCurrentRound().getRevealedWordCount());
        assertEquals(5, session.getCurrentRound().getRemainingTossupTimerSeconds()); // fully read: the buzz window starts
    }

    @Test
    void wordWeightsFollowLengthAndPunctuation() {
        assertEquals(0.75, QuestionTokenizer.wordWeight("the"), 1e-9);
        assertEquals(1.35, QuestionTokenizer.wordWeight("extraordinarily"), 1e-9);
        assertEquals(1.25, QuestionTokenizer.wordWeight("cat,"), 1e-9);
        assertEquals(1.75, QuestionTokenizer.wordWeight("cat.\u201D"), 1e-9);
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

    /* ---------------- auto-judged multiplayer: answer window + auto-advance ---------------- */

    private GameSession autoSession(GameMode mode, RoundState state) {
        GameSession session = buildSession(mode, state, 20, 20, 4);
        session.getGameSettings().getTimerSettings().setAnswerTimerSeconds(2);
        session.getGameSettings().getTimerSettings().setAdvanceDelaySeconds(3);
        Player owner = Player.builder().playerId("owner").playerMode(PlayerMode.BUZZER).isGameOwner(true).build();
        Player other = Player.builder().playerId("other").playerMode(PlayerMode.BUZZER).build();
        session.setPlayerList(new java.util.ArrayList<>(List.of(owner, other)));
        return session;
    }

    private List<TimerUpdate> timerUpdates(String type) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, org.mockito.Mockito.atLeast(0)).convertAndSend(anyString(), captor.capture());
        return captor.getAllValues().stream()
                .filter(TimerUpdate.class::isInstance).map(TimerUpdate.class::cast)
                .filter(u -> type.equals(u.getTimerType())).toList();
    }

    @Test
    void answerWindowCountsDownForEveryoneThenSubmitsAnEmptyAnswerAsTheBuzzer() {
        GameSession session = autoSession(GameMode.FREE_FOR_ALL, RoundState.AWAITING_ANSWER);
        Buzz buzz = new Buzz();
        buzz.setPlayerId("other");
        buzz.setTeamId("t");
        session.getCurrentRound().setCurrentBuzz(buzz);
        session.getCurrentRound().startAnswerTimer(2);

        gameTimerService.processTimers();
        assertEquals(1, session.getCurrentRound().getRemainingAnswerTimerSeconds());
        verify(messageService, never()).sendMessage(any());

        gameTimerService.processTimers();
        assertEquals(null, session.getCurrentRound().getRemainingAnswerTimerSeconds());
        ArgumentCaptor<SubmitAnswer> sent = ArgumentCaptor.forClass(SubmitAnswer.class);
        verify(messageService).sendMessage(sent.capture());
        assertEquals("other", sent.getValue().getOriginatingPlayerId());
        assertEquals("", sent.getValue().getAnswerText());
        assertEquals(List.of(1, 0), timerUpdates("ANSWER").stream().map(TimerUpdate::getRemainingSeconds).toList());
    }

    @Test
    void finishedRoundAdvancesOnTheServerAfterTheDelayExactlyOnce() {
        GameSession session = autoSession(GameMode.AUTO_PROCTOR, RoundState.COMPLETED);

        gameTimerService.processTimers(); // arms: 3
        gameTimerService.processTimers(); // 2
        gameTimerService.processTimers(); // 1
        verify(messageService, never()).sendMessage(any());

        gameTimerService.processTimers(); // fires
        ArgumentCaptor<AdvanceRound> sent = ArgumentCaptor.forClass(AdvanceRound.class);
        verify(messageService).sendMessage(sent.capture());
        assertEquals("owner", sent.getValue().getOriginatingPlayerId());

        // Still COMPLETED until the AdvanceRound is processed: never fires twice.
        gameTimerService.processTimers();
        gameTimerService.processTimers();
        verify(messageService, times(1)).sendMessage(any());
        assertEquals(List.of(3, 2, 1, 0), timerUpdates("ADVANCE").stream().map(TimerUpdate::getRemainingSeconds).toList());
    }

    @Test
    void classicModeNeverAutoAdvances() {
        GameSession session = autoSession(GameMode.QUIZ_BOWL_CLASSIC, RoundState.COMPLETED);
        for (int i = 0; i < 10; i++) {
            gameTimerService.processTimers();
        }
        verify(messageService, never()).sendMessage(any());
        assertFalse(session.getCurrentRound().isAutoAdvanceArmed());
    }

    @Test
    void pendingBonusStartsOnTheServerAfterTheDelayExactlyOnce() {
        GameSession session = autoSession(GameMode.FREE_FOR_ALL, RoundState.BONUS_PENDING);

        gameTimerService.processTimers(); // arms: 3
        gameTimerService.processTimers(); // 2
        gameTimerService.processTimers(); // 1
        verify(messageService, never()).sendMessage(any());

        gameTimerService.processTimers(); // fires
        ArgumentCaptor<StartBonus> sent = ArgumentCaptor.forClass(StartBonus.class);
        verify(messageService).sendMessage(sent.capture());
        assertEquals("owner", sent.getValue().getOriginatingPlayerId());

        gameTimerService.processTimers();
        verify(messageService, times(1)).sendMessage(any());
        assertEquals(List.of(3, 2, 1, 0), timerUpdates("BONUS_START").stream().map(TimerUpdate::getRemainingSeconds).toList());
        // No other clock runs while the bonus is pending.
        assertEquals(null, session.getCurrentRound().getRemainingBonusTimerSeconds());
        assertEquals(null, session.getCurrentRound().getRemainingTossupTimerSeconds());
    }
}
