package com.soulsoftworks.sockbowlgame.model.game;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Builder
@AllArgsConstructor
public class GameSettings {
    ProctorType proctorType;
    GameMode gameMode;
    int numPlayers;
    int numTeams;
}
