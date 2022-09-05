package com.soulsoftworks.sockbowlgame.websocket.model.request;

import com.soulsoftworks.sockbowlgame.game.model.GameSettings;
import lombok.Data;

@Data
public class CreateGameRequest {
    GameSettings gameSettings;
}
