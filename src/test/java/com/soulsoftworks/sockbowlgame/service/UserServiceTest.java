package com.soulsoftworks.sockbowlgame.service;

import com.soulsoftworks.sockbowlgame.model.entity.User;
import com.soulsoftworks.sockbowlgame.repository.UserGameHistoryRepository;
import com.soulsoftworks.sockbowlgame.repository.UserRepository;
import com.soulsoftworks.sockbowlgame.repository.UserStatsRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP INT1-PROFILE: two requests for the same never-before-seen user (e.g.
 * the profile page's parallel stats + history calls) can both reach
 * {@link UserService#findOrCreateUser} before either has inserted the row.
 * Both see no row and both attempt an INSERT; the loser must recover by
 * re-reading the winner's row instead of letting the database's unique
 * constraint violation escape as a request failure (INT1 live gate,
 * admin-usage.spec.ts's provisionUser()).
 */
class UserServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserStatsRepository userStatsRepository = mock(UserStatsRepository.class);
    private final UserGameHistoryRepository userGameHistoryRepository = mock(UserGameHistoryRepository.class);
    private final UserService service =
            new UserService(userRepository, userStatsRepository, userGameHistoryRepository);

    @Test
    void findOrCreateUserReturnsExistingRowWithoutInserting() {
        User existing = User.builder().id(UUID.randomUUID()).keycloakId("kc-1")
                .email("a@b.com").name("A").build();
        when(userRepository.findByKeycloakId("kc-1")).thenReturn(Optional.of(existing));

        User result = service.findOrCreateUser("kc-1", "a@b.com", "A");

        assertSame(existing, result);
        verify(userRepository, times(0)).save(any());
    }

    @Test
    void findOrCreateUserCreatesRowWhenNoneExists() {
        when(userRepository.findByKeycloakId("kc-1")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(UUID.randomUUID());
            return u;
        });

        User result = service.findOrCreateUser("kc-1", "a@b.com", "A");

        assertEquals("kc-1", result.getKeycloakId());
        verify(userRepository, times(1)).save(any(User.class));
    }

    /**
     * Reproduces the race from the INT1 live gate: this call's own SELECT
     * found nothing, but by the time it INSERTs, a concurrent request for the
     * same keycloak id has already committed its row, so the INSERT fails the
     * unique constraint. The method must not propagate that failure -- it
     * should re-read and return the row the other request created.
     */
    @Test
    void findOrCreateUserRecoversWhenConcurrentRequestWinsTheInsertRace() {
        User winnersRow = User.builder().id(UUID.randomUUID()).keycloakId("kc-1")
                .email("a@b.com").name("A").build();
        when(userRepository.findByKeycloakId("kc-1"))
                .thenReturn(Optional.empty())       // this call's own initial lookup
                .thenReturn(Optional.of(winnersRow)); // re-read after losing the race
        when(userRepository.save(any(User.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"uk6dotkott2kjsp8vw4d0m25fb7\""));

        User result = service.findOrCreateUser("kc-1", "a@b.com", "A");

        assertSame(winnersRow, result);
        verify(userRepository, times(2)).findByKeycloakId("kc-1");
    }

    @Test
    void findOrCreateUserRethrowsWhenRowStillMissingAfterConstraintViolation() {
        // Pathological case (constraint violation for some other reason, e.g.
        // the unique email is already taken by a *different* keycloak id):
        // there's nothing to recover, so the original failure surfaces.
        when(userRepository.findByKeycloakId("kc-1")).thenReturn(Optional.empty());
        DataIntegrityViolationException failure = new DataIntegrityViolationException("boom");
        when(userRepository.save(any(User.class))).thenThrow(failure);

        DataIntegrityViolationException thrown = assertThrows(DataIntegrityViolationException.class,
                () -> service.findOrCreateUser("kc-1", "a@b.com", "A"));
        assertSame(failure, thrown);
    }
}
