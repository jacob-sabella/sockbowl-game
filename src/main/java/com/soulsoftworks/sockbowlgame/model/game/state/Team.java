package com.soulsoftworks.sockbowlgame.model.game.state;

import lombok.Data;

import java.util.List;

@Data
public class Team {
    private String teamName;
    private List<Player> teamPlayers;
}
