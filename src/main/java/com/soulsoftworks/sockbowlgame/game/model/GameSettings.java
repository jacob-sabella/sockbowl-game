package com.soulsoftworks.sockbowlgame.game.model;

import lombok.Data;

@Data
public class GameSettings {
    ProctorType proctorType;
    int numPlayers;
    int numTeams;
}
