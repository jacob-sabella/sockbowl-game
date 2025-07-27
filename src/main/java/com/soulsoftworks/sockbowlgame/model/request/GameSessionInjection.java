package com.soulsoftworks.sockbowlgame.model.request;

import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class GameSessionInjection {
    private PlayerIdentifiers playerIdentifiers;
    private String gameSessionId;
    private GameSession gameSession;
}
