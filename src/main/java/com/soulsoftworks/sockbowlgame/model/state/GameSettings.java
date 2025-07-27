package com.soulsoftworks.sockbowlgame.model.state;

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
}
