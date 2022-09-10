package com.soulsoftworks.sockbowlgame.game.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;

import java.util.UUID;

@Data
@RedisHash("GameSession")
@AllArgsConstructor
public class GameSession {
    @Id
    UUID sessionUuid;
    GameSettings gameSettings;
}
