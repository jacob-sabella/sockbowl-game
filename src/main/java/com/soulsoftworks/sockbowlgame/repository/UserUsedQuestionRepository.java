package com.soulsoftworks.sockbowlgame.repository;

import com.soulsoftworks.sockbowlgame.model.entity.UserUsedQuestion;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "true")
public interface UserUsedQuestionRepository extends JpaRepository<UserUsedQuestion, UUID> {

    @Query("select u.remoteId from UserUsedQuestion u where u.userId = :userId")
    List<String> findRemoteIdsByUserId(@Param("userId") UUID userId);

    List<UserUsedQuestion> findByUserIdAndRemoteIdIn(UUID userId, Collection<String> remoteIds);
}
