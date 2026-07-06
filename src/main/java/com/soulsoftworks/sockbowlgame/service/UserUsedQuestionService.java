package com.soulsoftworks.sockbowlgame.service;

import com.soulsoftworks.sockbowlgame.model.entity.UserUsedQuestion;
import com.soulsoftworks.sockbowlgame.repository.UserUsedQuestionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Tracks which source questions (qbreader ids) a user has already seen, so new
 * random packets can exclude them. Only loaded when auth is enabled.
 */
@Service
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "true")
public class UserUsedQuestionService {

    private final UserUsedQuestionRepository repository;

    public UserUsedQuestionService(UserUsedQuestionRepository repository) {
        this.repository = repository;
    }

    /** The qbreader ids this user has already been served. */
    public Set<String> getUsedRemoteIds(UUID userId) {
        return new HashSet<>(repository.findRemoteIdsByUserId(userId));
    }

    /**
     * Record newly-served qbreader ids for a user, skipping any already stored.
     *
     * @return how many new ids were recorded
     */
    @Transactional
    public int recordUsed(UUID userId, Collection<String> remoteIds) {
        if (remoteIds == null || remoteIds.isEmpty()) {
            return 0;
        }
        Set<String> distinct = remoteIds.stream()
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
        if (distinct.isEmpty()) {
            return 0;
        }
        Set<String> existing = repository.findByUserIdAndRemoteIdIn(userId, distinct).stream()
                .map(UserUsedQuestion::getRemoteId)
                .collect(Collectors.toSet());
        List<UserUsedQuestion> toSave = distinct.stream()
                .filter(id -> !existing.contains(id))
                .map(id -> UserUsedQuestion.builder().userId(userId).remoteId(id).build())
                .toList();
        repository.saveAll(toSave);
        return toSave.size();
    }
}
