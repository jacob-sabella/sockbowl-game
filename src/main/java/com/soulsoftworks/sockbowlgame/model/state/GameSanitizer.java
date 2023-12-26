package com.soulsoftworks.sockbowlgame.model.state;

import com.soulsoftworks.sockbowlgame.util.DeepCopyUtil;

import java.util.ArrayList;

public class GameSessionSanitizer {

    public static GameSession sanitize(GameSession gameSession, PlayerMode playerMode){
        // Create a deep copy of the game session
        GameSession sanitizedGameSession = DeepCopyUtil.deepCopy(gameSession, GameSession.class);

        // Now modify the sanitizedGameSession as needed
        sanitizedGameSession.getPlayerList().forEach(player -> player.setPlayerSecret(""));

        if(playerMode != PlayerMode.PROCTOR){
            if(sanitizedGameSession.getCurrentMatch().getPacket() != null){
                sanitizedGameSession.getCurrentMatch().getPacket().setTossups(null);
                sanitizedGameSession.getCurrentMatch().getPacket().setBonuses(null);
            }
        }

        return sanitizedGameSession;
    }
}
