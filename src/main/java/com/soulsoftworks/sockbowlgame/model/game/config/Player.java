package com.soulsoftworks.sockbowlgame.model.game.config;

import lombok.Data;

@Data
public class Player {
    private PlayerMode playerMode;
    private boolean isGameOwner;
    private String playerId;
    private String playerSecret;
    private String name;
}
