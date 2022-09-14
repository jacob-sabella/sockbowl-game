package com.soulsoftworks.sockbowlgame.game.model;

import lombok.Data;

@Data
public class GameSettings {
    ProctorType proctorType;
    GameMode gameMode;
    int numPlayers;
    int numTeams;
}
