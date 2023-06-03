package com.soulsoftworks.sockbowlgame.repository;

import com.redis.om.spring.repository.RedisDocumentRepository;
import com.soulsoftworks.sockbowlgame.model.game.state.GameSession;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GameSessionRepository extends RedisDocumentRepository<GameSession, String> {
    Optional<GameSession> findGameSessionByJoinCode(String joinCode);

    Optional<GameSession> findGameSessionById(String id);

}
