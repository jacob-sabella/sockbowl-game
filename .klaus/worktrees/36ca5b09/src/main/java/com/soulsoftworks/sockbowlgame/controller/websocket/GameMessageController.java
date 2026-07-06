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
        answerOutcome.setOriginatingPlayerId(gameSessionInjection.getPlayerIdentifiers().getSimpSessionId());
        answerOutcome.setGameSessionId(gameSessionInjection.getGameSessionId());

        messageService.sendMessage(answerOutcome);
    }

    @MessageMapping("/player-incoming-buzz")
    public void playerIncomingBuzz(GameSessionInjection gameSessionInjection, PlayerIncomingBuzz playerIncomingBuzz) {
        playerIncomingBuzz.setOriginatingPlayerId(gameSessionInjection.getPlayerIdentifiers().getSimpSessionId());
        playerIncomingBuzz.setGameSessionId(gameSessionInjection.getGameSessionId());

        messageService.sendMessage(playerIncomingBuzz);
    }

    @MessageMapping("/timeout-round")
    public void timeoutRound(GameSessionInjection gameSessionInjection, TimeoutRound timeoutRound) {
        timeoutRound.setOriginatingPlayerId(gameSessionInjection.getPlayerIdentifiers().getSimpSessionId());
        timeoutRound.setGameSessionId(gameSessionInjection.getGameSessionId());

        messageService.sendMessage(timeoutRound);
    }

    @MessageMapping("/finished-reading")
    public void finishedReading(GameSessionInjection gameSessionInjection, FinishedReading finishedReading) {
        finishedReading.setOriginatingPlayerId(gameSessionInjection.getPlayerIdentifiers().getSimpSessionId());
        finishedReading.setGameSessionId(gameSessionInjection.getGameSessionId());

        messageService.sendMessage(finishedReading);
    }

    @MessageMapping("/advance-round")
    public void advanceRound(GameSessionInjection gameSessionInjection) {

        AdvanceRound advanceRound = AdvanceRound
                .builder()
                .originatingPlayerId(gameSessionInjection.getPlayerIdentifiers().getSimpSessionId())
                .gameSessionId(gameSessionInjection.getGameSessionId())
                .build();

        messageService.sendMessage(advanceRound);
    }

    @MessageMapping("/bonus-part-outcome")
    public void bonusPartOutcome(GameSessionInjection gameSessionInjection, BonusPartOutcome bonusPartOutcome) {
        bonusPartOutcome.setOriginatingPlayerId(gameSessionInjection.getPlayerIdentifiers().getSimpSessionId());
        bonusPartOutcome.setGameSessionId(gameSessionInjection.getGameSessionId());

        messageService.sendMessage(bonusPartOutcome);
    }

    @MessageMapping("/finished-reading-bonus-preamble")
    public void finishedReadingBonusPreamble(GameSessionInjection gameSessionInjection, FinishedReadingBonusPreamble finishedReadingBonusPreamble) {
        finishedReadingBonusPreamble.setOriginatingPlayerId(gameSessionInjection.getPlayerIdentifiers().getSimpSessionId());
        finishedReadingBonusPreamble.setGameSessionId(gameSessionInjection.getGameSessionId());

        messageService.sendMessage(finishedReadingBonusPreamble);
    }

    @MessageMapping("/finished-reading-bonus-part")
    public void finishedReadingBonusPart(GameSessionInjection gameSessionInjection, FinishedReadingBonusPart finishedReadingBonusPart) {
        finishedReadingBonusPart.setOriginatingPlayerId(gameSessionInjection.getPlayerIdentifiers().getSimpSessionId());
        finishedReadingBonusPart.setGameSessionId(gameSessionInjection.getGameSessionId());

        messageService.sendMessage(finishedReadingBonusPart);
    }

    @MessageMapping("/timeout-bonus-part")
    public void timeoutBonusPart(GameSessionInjection gameSessionInjection, TimeoutBonusPart timeoutBonusPart) {
        timeoutBonusPart.setOriginatingPlayerId(gameSessionInjection.getPlayerIdentifiers().getSimpSessionId());
        timeoutBonusPart.setGameSessionId(gameSessionInjection.getGameSessionId());

        messageService.sendMessage(timeoutBonusPart);
    }

}
