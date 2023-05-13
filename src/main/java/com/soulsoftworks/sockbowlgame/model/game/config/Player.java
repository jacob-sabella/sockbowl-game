package com.soulsoftworks.sockbowlgame.model.game.config;

import lombok.Data;

@Data
public class Player {
    private PlayerMode playerMode;
    private String playerId;
    private String name;
}
