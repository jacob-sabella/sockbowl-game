package com.soulsoftworks.sockbowlgame.model.state;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PlayerSettings {
    int maxPlayersPerTeam;
    int numTeams;

    public int getMaxPlayers(){
        return numTeams * maxPlayersPerTeam;
    }
}
