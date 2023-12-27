package com.soulsoftworks.sockbowlgame.controller.websocket;

import com.soulsoftworks.sockbowlgame.model.socket.in.game.*;
import com.soulsoftworks.sockbowlgame.model.request.GameSessionInjection;
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

    @MessageMapping("/answer-correct")
    public void answerCorrect(GameSessionInjection gameSessionInjection, AnswerCorrect answerCorrect) {
        answerCorrect.setOriginatingPlayerId(gameSessionInjection.getPlayerIdentifiers().getSimpSessionId());
        answerCorrect.setGameSessionId(gameSessionInjection.getGameSessionId());

        messageService.sendMessage(answerCorrect);
    }

    @MessageMapping("/answer-incorrect")
    public void answerIncorrect(GameSessionInjection gameSessionInjection, AnswerIncorrect answerIncorrect) {
        answerIncorrect.setOriginatingPlayerId(gameSessionInjection.getPlayerIdentifiers().getSimpSessionId());
        answerIncorrect.setGameSessionId(gameSessionInjection.getGameSessionId());

        messageService.sendMessage(answerIncorrect);
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


}
