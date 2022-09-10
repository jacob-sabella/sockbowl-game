package com.soulsoftworks.sockbowlgame.redis.repository;

import com.soulsoftworks.sockbowlgame.game.model.GameSession;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GameSessionRepository extends CrudRepository<GameSession, String> {

}
