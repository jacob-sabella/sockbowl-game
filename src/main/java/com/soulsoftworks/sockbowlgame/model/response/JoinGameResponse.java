package com.soulsoftworks.sockbowlgame.model.response;

import com.soulsoftworks.sockbowlgame.model.state.JoinStatus;
import lombok.Data;

import java.util.UUID;

@Data
public class JoinGameResponse {
    JoinStatus joinStatus;
    String gameSessionId;
    String playerSecret;
    String playerSessionId;
}
