package com.soulsoftworks.sockbowlgame.model.response;

import com.soulsoftworks.sockbowlgame.model.state.JoinStatus;
import lombok.Data;

@Data
public class JoinGameResponse {
    JoinStatus joinStatus;
    String gameSessionId;
    String playerSecret;
}
