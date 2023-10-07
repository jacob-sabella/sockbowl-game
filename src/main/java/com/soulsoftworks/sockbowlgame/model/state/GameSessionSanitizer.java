package com.soulsoftworks.sockbowlgame.model.state;

import java.util.ArrayList;

public class GameSessionSanitizer {

    public static GameSession sanitize(GameSession gameSession, PlayerMode playerMode){
        GameSession sanitizedGameSession = gameSession.toBuilder().build();

        // Remove player secrets
        gameSession.getPlayerList().forEach(player -> player.setPlayerSecret(""));

        if(playerMode != PlayerMode.PROCTOR){
            if(gameSession.getCurrentMatch().getPacket() != null){
                // Remove question/answer
                gameSession.getCurrentMatch().getPacket().setTossups(null);
                gameSession.getCurrentMatch().getPacket().setBonuses(null);
            }
        }

        return sanitizedGameSession;
    }
}
