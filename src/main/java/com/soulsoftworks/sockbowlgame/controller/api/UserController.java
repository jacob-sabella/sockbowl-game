package com.soulsoftworks.sockbowlgame.controller.api;

import com.soulsoftworks.sockbowlgame.model.entity.User;
import com.soulsoftworks.sockbowlgame.model.entity.UserGameHistory;
import com.soulsoftworks.sockbowlgame.model.entity.UserStats;
import com.soulsoftworks.sockbowlgame.service.UserService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

/**
 * Controller for user-related endpoints.
 * Provides access to user statistics and game history.
 *
 * Only active when sockbowl.auth.enabled=true.
 */
@RestController
@RequestMapping("/api/v1/user")
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "true")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * Get the current user's profile information.
     *
     * @param jwt JWT token from Keycloak
     * @return User profile information
     */
    @GetMapping("/profile")
    public ResponseEntity<Map<String, Object>> getUserProfile(@AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }

        String keycloakId = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        String name = jwt.getClaimAsString("name");

        User user = userService.findOrCreateUser(keycloakId, email, name);
        userService.updateLastLogin(user.getId());

        Map<String, Object> profile = new HashMap<>();
        profile.put("id", user.getId().toString());
        profile.put("keycloakId", user.getKeycloakId());
        profile.put("email", user.getEmail());
        profile.put("name", user.getName());
        profile.put("createdAt", user.getCreatedAt());
        profile.put("lastLoginAt", user.getLastLoginAt());

        return ResponseEntity.ok(profile);
    }

    /**
     * Get the current user's game statistics.
     *
     * @param jwt JWT token from Keycloak
     * @return User statistics
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getUserStats(@AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }

        String keycloakId = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        String name = jwt.getClaimAsString("name");

        User user = userService.findOrCreateUser(keycloakId, email, name);
        userService.updateLastLogin(user.getId());

        UserStats stats = userService.getUserStats(user.getId());

        Map<String, Object> statsResponse = new HashMap<>();
        statsResponse.put("totalGames", stats.getTotalGames());
        statsResponse.put("totalWins", stats.getTotalWins());
        statsResponse.put("totalBuzzes", stats.getTotalBuzzes());
        statsResponse.put("correctBuzzes", stats.getCorrectBuzzes());
        statsResponse.put("updatedAt", stats.getUpdatedAt());

        // Calculate derived statistics
        if (stats.getTotalGames() > 0) {
            double winRate = (double) stats.getTotalWins() / stats.getTotalGames() * 100;
            statsResponse.put("winRate", Math.round(winRate * 100.0) / 100.0);
        } else {
            statsResponse.put("winRate", 0.0);
        }

        if (stats.getTotalBuzzes() > 0) {
            double accuracy = (double) stats.getCorrectBuzzes() / stats.getTotalBuzzes() * 100;
            statsResponse.put("accuracy", Math.round(accuracy * 100.0) / 100.0);
        } else {
            statsResponse.put("accuracy", 0.0);
        }

        return ResponseEntity.ok(statsResponse);
    }

    /**
     * Get the current user's game history with pagination.
     *
     * @param jwt JWT token from Keycloak
     * @param pageable pagination parameters
     * @return Page of game history records
     */
    @GetMapping("/history")
    public ResponseEntity<Page<Map<String, Object>>> getUserHistory(
            @AuthenticationPrincipal Jwt jwt,
            Pageable pageable) {

        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }

        String keycloakId = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        String name = jwt.getClaimAsString("name");

        User user = userService.findOrCreateUser(keycloakId, email, name);
        userService.updateLastLogin(user.getId());

        Page<UserGameHistory> history = userService.getUserGameHistory(user.getId(), pageable);

        // Convert to response DTOs
        Page<Map<String, Object>> historyResponse = history.map(h -> {
            Map<String, Object> historyItem = new HashMap<>();
            historyItem.put("id", h.getId().toString());
            historyItem.put("gameSessionId", h.getGameSessionId());
            historyItem.put("playerSessionId", h.getPlayerSessionId());
            historyItem.put("joinedAt", h.getJoinedAt());
            historyItem.put("finalScore", h.getFinalScore());
            historyItem.put("teamName", h.getTeamName());
            return historyItem;
        });

        return ResponseEntity.ok(historyResponse);
    }
}
