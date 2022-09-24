package com.soulsoftworks.sockbowlgame.model.game;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class GameSettings {
    ProctorType proctorType;
    GameMode gameMode;
    int numPlayers;
    int numTeams;
}
