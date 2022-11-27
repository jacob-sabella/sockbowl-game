package com.soulsoftworks.sockbowlgame.model.game;

import lombok.Data;

@Data
public class Player {
    private PlayerMode playerMode;
    private String sessionId;
    private String name;
}
