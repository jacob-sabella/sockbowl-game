package com.soulsoftworks.sockbowlgame.model.game.state;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
public class Team {
    private String teamId = UUID.randomUUID().toString();
    private String teamName;
    private List<Player> teamPlayers = new ArrayList<>();
}
