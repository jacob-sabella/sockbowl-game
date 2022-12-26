package com.soulsoftworks.sockbowlgame.model.response;

import com.soulsoftworks.sockbowlgame.model.game.JoinStatus;
import lombok.Data;

@Data
public class JoinGameResponse {
    JoinStatus joinStatus;
    String sessionId;
}
