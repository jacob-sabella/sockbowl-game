package com.soulsoftworks.sockbowlgame.service;

import com.soulsoftworks.sockbowlgame.model.entity.UserUsedQuestion;
import com.soulsoftworks.sockbowlgame.repository.UserUsedQuestionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserUsedQuestionServiceTest {

    private final UserUsedQuestionRepository repo = mock(UserUsedQuestionRepository.class);
    private final UserUsedQuestionService service = new UserUsedQuestionService(repo);
    private final UUID userId = UUID.randomUUID();

    @Test
    void recordsOnlyNewIdsSkippingExistingBlankAndNull() {
        // "b" already stored → should not be re-saved; null/blank filtered out.
        when(repo.findByUserIdAndRemoteIdIn(eq(userId), any()))
                .thenReturn(List.of(UserUsedQuestion.builder().userId(userId).remoteId("b").build()));

        int recorded = service.recordUsed(userId, java.util.Arrays.asList("a", "b", "a", null, "  ", "c"));

        assertEquals(2, recorded, "only a and c are new");
        verify(repo).saveAll(argThat(saved -> {
            Set<String> ids = toIds(saved);
            return ids.equals(Set.of("a", "c"));
        }));
    }

    @Test
    void emptyInputRecordsNothing() {
        assertEquals(0, service.recordUsed(userId, List.of()));
        assertEquals(0, service.recordUsed(userId, null));
    }

    private static Set<String> toIds(Iterable<UserUsedQuestion> saved) {
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        saved.forEach(u -> ids.add(u.getRemoteId()));
        return ids;
    }

    private static <T> T argThat(org.mockito.ArgumentMatcher<T> m) {
        return org.mockito.ArgumentMatchers.argThat(m);
    }
}
