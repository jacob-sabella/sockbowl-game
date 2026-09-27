package com.soulsoftworks.sockbowlgame.controller.websocket;

import com.soulsoftworks.sockbowlgame.model.socket.in.game.*;
import com.soulsoftworks.sockbowlgame.model.request.GameSessionInjection;
import com.soulsoftworks.sockbowlgame.model.socket.in.game.AdvanceRound;
import com.soulsoftworks.sockbowlgame.service.MessageService;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

@Controller
@MessageMapping("game")
public class GameMessageController {
    private final MessageService messageService;

    public GameMessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @MessageMapping("/answer-outcome")
    public void answerCorrect(GameSessionInjection gameSessionInjection, AnswerOutcome answerOutcome) {
        answerOutcome.stampOrigin(gameSessionInjection);

        messageService.sendMessage(answerOutcome);
    }

    @MessageMapping("/player-incoming-buzz")
    public void playerIncomingBuzz(GameSessionInjection gameSessionInjection, PlayerIncomingBuzz playerIncomingBuzz) {
        playerIncomingBuzz.stampOrigin(gameSessionInjection);

        messageService.sendMessage(playerIncomingBuzz);
    }

    @MessageMapping("/submit-answer")
    public void submitAnswer(GameSessionInjection gameSessionInjection, SubmitAnswer submitAnswer) {
        submitAnswer.stampOrigin(gameSessionInjection);

        messageService.sendMessage(submitAnswer);
    }

    @MessageMapping("/timeout-round")
    public void timeoutRound(GameSessionInjection gameSessionInjection, TimeoutRound timeoutRound) {
        timeoutRound.stampOrigin(gameSessionInjection);

        messageService.sendMessage(timeoutRound);
    }

    @MessageMapping("/finished-reading")
    public void finishedReading(GameSessionInjection gameSessionInjection, FinishedReading finishedReading) {
        finishedReading.stampOrigin(gameSessionInjection);

        messageService.sendMessage(finishedReading);
    }

    @MessageMapping("/advance-round")
    public void advanceRound(GameSessionInjection gameSessionInjection) {

        AdvanceRound advanceRound = AdvanceRound
                .builder()
                .build();
        advanceRound.stampOrigin(gameSessionInjection);

        messageService.sendMessage(advanceRound);
    }

    @MessageMapping("/bonus-part-outcome")
    public void bonusPartOutcome(GameSessionInjection gameSessionInjection, BonusPartOutcome bonusPartOutcome) {
        bonusPartOutcome.stampOrigin(gameSessionInjection);

        messageService.sendMessage(bonusPartOutcome);
    }

    @MessageMapping("/finished-reading-bonus-preamble")
    public void finishedReadingBonusPreamble(GameSessionInjection gameSessionInjection, FinishedReadingBonusPreamble finishedReadingBonusPreamble) {
        finishedReadingBonusPreamble.stampOrigin(gameSessionInjection);

        messageService.sendMessage(finishedReadingBonusPreamble);
    }

    @MessageMapping("/finished-reading-bonus-part")
    public void finishedReadingBonusPart(GameSessionInjection gameSessionInjection, FinishedReadingBonusPart finishedReadingBonusPart) {
        finishedReadingBonusPart.stampOrigin(gameSessionInjection);

        messageService.sendMessage(finishedReadingBonusPart);
    }

    @MessageMapping("/timeout-bonus-part")
    public void timeoutBonusPart(GameSessionInjection gameSessionInjection, TimeoutBonusPart timeoutBonusPart) {
        timeoutBonusPart.stampOrigin(gameSessionInjection);

        messageService.sendMessage(timeoutBonusPart);
    }

    @MessageMapping("/start-bonus")
    public void startBonus(GameSessionInjection gameSessionInjection, StartBonus startBonus) {
        startBonus.stampOrigin(gameSessionInjection);

        messageService.sendMessage(startBonus);
    }

}
