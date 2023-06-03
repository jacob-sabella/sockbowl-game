package com.soulsoftworks.sockbowlgame.model.game.state;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Player {
    private PlayerMode playerMode;
    private boolean isGameOwner;
    @Builder.Default
    private PlayerStatus playerStatus = PlayerStatus.DISCONNECTED;
    private String playerId;
    private String playerSecret;
    private String name;
}
