package com.soulsoftworks.sockbowlgame.model.request;

import com.soulsoftworks.sockbowlgame.model.game.GameSettings;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class CreateGameRequest {
    GameSettings gameSettings = new GameSettings();
}
