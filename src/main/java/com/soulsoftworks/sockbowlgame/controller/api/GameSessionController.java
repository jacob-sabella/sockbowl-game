package com.soulsoftworks.sockbowlgame.controller.api;

import com.soulsoftworks.sockbowlgame.model.security.AuthenticatedUser;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.request.CreateGameRequest;
import com.soulsoftworks.sockbowlgame.model.request.JoinGameRequest;
import com.soulsoftworks.sockbowlgame.model.response.GameSessionIdentifiers;
import com.soulsoftworks.sockbowlgame.model.response.JoinGameResponse;
import com.soulsoftworks.sockbowlgame.service.SessionService;
import com.soulsoftworks.sockbowlgame.service.authorization.GameAuthorizationPolicy;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;


/**
 * Controller for all game related messages
 */
@RestController
@RequestMapping("api/v1/session/")
public class GameSessionController {

    private final SessionService sessionService;
    private final GameAuthorizationPolicy authorizationPolicy;

    @Value("${sockbowl.auth.enabled:false}")
    private boolean authEnabled;

    public GameSessionController(SessionService sessionService,
                                 GameAuthorizationPolicy authorizationPolicy) {
        this.sessionService = sessionService;
        this.authorizationPolicy = authorizationPolicy;
    }

    /**
     * Create a new game with the provided settings.
     *
     * <p>When authentication is enabled this requires an authenticated, non-banned
     * user with the create capability; the created session is owned by that user.
     * When authentication is disabled, guest creation is preserved.
     *
     * @param createGameRequest The settings to create the game with
     * @param jwt               The authenticated principal, or null in guest mode
     */
    @PostMapping("/create-new-game-session")
    public GameSessionIdentifiers createNewGame(@RequestBody CreateGameRequest createGameRequest,
                                                @AuthenticationPrincipal Jwt jwt){

        AuthenticatedUser identity = AuthenticatedUser.fromJwt(jwt);

        if (!authorizationPolicy.canCreateGame(identity)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "You are not allowed to create a game session.");
        }

        String gameOwnerId = identity.isAuthenticated() ? identity.getKeycloakId() : null;
        GameSession gameSession = sessionService.createNewGame(createGameRequest, gameOwnerId);

        return GameSessionIdentifiers.builder()
                .fromGameSession(gameSession)
                .build();
    }

    /**
     * Join a game with a join code (guest mode)
     */
    @PostMapping("/join-game-session-by-code")
    public JoinGameResponse joinGameSessionWithCode(@Valid @RequestBody JoinGameRequest joinGameRequest){
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

        if (!authEnabled) {
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

        // Reject banned users at the join edge
        authorizationPolicy.ensureNotBanned(AuthenticatedUser.fromJwt(jwt));

        // Validate only the join code is present
        if (joinGameRequest.getJoinCode() == null || joinGameRequest.getJoinCode().isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Join code is required"
            );
        }

        JoinGameResponse response = sessionService.addAuthenticatedUserToGameSession(joinGameRequest, jwt);
        return ResponseEntity.ok(response);
    }

}
