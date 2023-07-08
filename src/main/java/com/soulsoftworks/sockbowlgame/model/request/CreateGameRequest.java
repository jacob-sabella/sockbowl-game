package com.soulsoftworks.sockbowlgame.model.request;

import com.soulsoftworks.sockbowlgame.model.state.GameSettings;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateGameRequest {
    GameSettings gameSettings = new GameSettings();
}
