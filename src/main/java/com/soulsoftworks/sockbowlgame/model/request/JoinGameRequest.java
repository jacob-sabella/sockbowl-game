package com.soulsoftworks.sockbowlgame.model.request;

import com.soulsoftworks.sockbowlgame.model.game.PlayerMode;
import lombok.Data;

@Data
public class JoinGameRequest {
    private String sessionId;
    private String joinCode;
    private String name;
    private PlayerMode playerMode;
}
