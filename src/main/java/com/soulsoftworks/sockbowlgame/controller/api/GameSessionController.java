package com.soulsoftworks.sockbowlgame.controller.api;

import com.soulsoftworks.sockbowlgame.model.security.AuthenticatedUser;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.request.CreateGameRequest;
import com.soulsoftworks.sockbowlgame.model.request.JoinGameRequest;
import com.soulsoftworks.sockbowlgame.model.response.GameSessionIdentifiers;
import com.soulsoftworks.sockbowlgame.model.response.JoinGameResponse;
import com.soulsoftworks.sockbowlgame.service.SessionService;
import com.soulsoftworks.sockbowlgame.service.authorization.GameAuthorizationPolicy;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.stream.Collectors;


/**
 * REST entry points for creating and joining game sessions (plan m2-auth
 * section 2.6, decision D1).
 *
 * <p>Auth is additive. Guests (no bearer) may create and join; a caller that
 * presents a valid bearer acts as that user: owner rights, {@code game:host},
 * bans and identity binding all apply. A backend service-account token is never
 * a player. Unknown join codes are a 404 on both join paths.
 */
@RestController
@RequestMapping("/api/v1/session")
public class GameSessionController {

    private final SessionService sessionService;
    private final GameAuthorizationPolicy authorizationPolicy;
    private final Validator validator;

    public GameSessionController(SessionService sessionService,
                                 GameAuthorizationPolicy authorizationPolicy,
                                 Validator validator) {
        this.sessionService = sessionService;
        this.authorizationPolicy = authorizationPolicy;
        this.validator = validator;
    }

    /**
     * Create a new game with the provided settings.
     *
     * <p>Guests may host (D1). A signed-in user needs {@code game:host} and must
     * not be banned, and owns the created session; a service token is refused
     * (see {@link GameAuthorizationPolicy#canCreateGame}).
     *
     * @param createGameRequest The settings to create the game with
     * @param jwt               The validated bearer, or null for a guest
     */
    @PostMapping("/create-new-game-session")
    public GameSessionIdentifiers createNewGame(@RequestBody CreateGameRequest createGameRequest,
                                                @AuthenticationPrincipal Jwt jwt){

        AuthenticatedUser identity = authorizationPolicy.identityOf(jwt);

        if (!authorizationPolicy.canCreateGame(identity)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "You are not allowed to create a game session.");
        }

        String gameOwnerId = identity.isUser() ? identity.getKeycloakId() : null;
        GameSession gameSession = sessionService.createNewGame(createGameRequest, gameOwnerId);

        return GameSessionIdentifiers.builder()
                .fromGameSession(gameSession)
                .build();
    }

    /**
     * Join a game with a join code.
     *
     * <p>Without a bearer this is a guest join, and {@code name} is required.
     * With a valid bearer (auth enabled) it is delegated to the authenticated
     * join, so a signed-in user can never end up in a game as an anonymous guest
     * (which would sidestep bans and ownership).
     */
    @PostMapping("/join-game-session-by-code")
    public JoinGameResponse joinGameSessionWithCode(@RequestBody JoinGameRequest joinGameRequest,
                                                    @AuthenticationPrincipal Jwt jwt){
        if (authorizationPolicy.isAuthEnabled() && jwt != null) {
            return joinAuthenticated(joinGameRequest, jwt);
        }

        Set<ConstraintViolation<JoinGameRequest>> violations = validator.validate(joinGameRequest);
        if (!violations.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, violations.stream()
                    .map(ConstraintViolation::getMessage)
                    .sorted()
                    .collect(Collectors.joining(", ")));
        }
        return sessionService.addPlayerToGameSessionWithJoinCode(joinGameRequest);
    }

    /**
     * Join a game as an authenticated user with Keycloak token.
     * Requires authentication via Bearer token in Authorization header.
     *
     * Only available when sockbowl.auth.enabled=true.
     *
     * Note: Name field is not required - it will be extracted from the JWT token.
     *
     * @param joinGameRequest Join game request from client (name field is optional)
     * @param jwt JWT token from Keycloak (injected by Spring Security)
     * @return JoinGameResponse with user information
     */
    @PostMapping("/join-game-session-authenticated")
    public ResponseEntity<JoinGameResponse> joinGameSessionAuthenticated(
            @RequestBody JoinGameRequest joinGameRequest,
            @AuthenticationPrincipal Jwt jwt) {

        if (!authorizationPolicy.isAuthEnabled()) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Authentication is not enabled. Set sockbowl.auth.enabled=true to use this feature."
            );
        }

        if (jwt == null) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Authentication required. Please provide a valid Bearer token."
            );
        }

        return ResponseEntity.ok(joinAuthenticated(joinGameRequest, jwt));
    }

    private JoinGameResponse joinAuthenticated(JoinGameRequest joinGameRequest, Jwt jwt) {
        AuthenticatedUser identity = authorizationPolicy.identityOf(jwt);

        // A backend service account is not a player.
        if (authorizationPolicy.isServiceIdentity(identity)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Service accounts cannot join games.");
        }

        // Reject banned users at the join edge
        authorizationPolicy.ensureNotBanned(identity);

        // Validate only the join code is present
        if (joinGameRequest.getJoinCode() == null || joinGameRequest.getJoinCode().isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Join code is required"
            );
        }

        return sessionService.addAuthenticatedUserToGameSession(joinGameRequest, jwt);
    }

}
