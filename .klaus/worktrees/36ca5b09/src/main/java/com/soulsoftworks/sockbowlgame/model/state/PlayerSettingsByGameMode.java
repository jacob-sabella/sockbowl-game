package com.soulsoftworks.sockbowlgame.model.state;

import java.util.HashMap;
import java.util.Map;

public class PlayerSettingsByGameMode {
    public static Map<GameMode, PlayerSettings> PLAYER_SETTINGS_BY_GAME_MODE;

    static {
        PLAYER_SETTINGS_BY_GAME_MODE = new HashMap<>();
        PLAYER_SETTINGS_BY_GAME_MODE.put(GameMode.QUIZ_BOWL_CLASSIC, new PlayerSettings(4, 2));
    }
}

