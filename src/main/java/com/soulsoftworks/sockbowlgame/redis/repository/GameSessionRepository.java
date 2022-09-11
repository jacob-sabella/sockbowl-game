package com.soulsoftworks.sockbowlgame.redis.repository;

import com.redis.om.spring.repository.RedisDocumentRepository;
import com.soulsoftworks.sockbowlgame.game.model.GameSession;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GameSessionRepository extends RedisDocumentRepository<GameSession, String> {
    Optional<GameSession> findGameSessionByJoinCode(String joinCode);
}
