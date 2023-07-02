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


    public Player findPlayerInTeamWithId(String playerId){
        return teamPlayers.stream()
                .filter(player -> player.getPlayerId().equals(playerId))
                .findFirst()
                .orElse(null);
    }

    public void addPlayerToTeam(Player player){
        teamPlayers.add(player);
    }

    public void removePlayerFromTeam(String playerId) {
        teamPlayers.removeIf(player -> player.getPlayerId().equals(playerId));
    }

    public boolean isPlayerOnTeam(String playerId){
        return teamPlayers.stream()
                .anyMatch(player -> player.getPlayerId().equals(playerId));
    }
}
