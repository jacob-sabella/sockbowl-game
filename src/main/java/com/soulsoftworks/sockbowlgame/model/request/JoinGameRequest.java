package com.soulsoftworks.sockbowlgame.model.request;

import com.soulsoftworks.sockbowlgame.model.state.PlayerMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JoinGameRequest {
    String playerSessionId;
    String joinCode;
    String name;
    PlayerMode playerMode;
}
