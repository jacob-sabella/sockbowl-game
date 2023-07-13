package com.soulsoftworks.sockbowlgame.model.state;

import lombok.Data;

@Data
public class Buzz {
    private String playerId;
    private String teamId;
    private boolean correct;
}
