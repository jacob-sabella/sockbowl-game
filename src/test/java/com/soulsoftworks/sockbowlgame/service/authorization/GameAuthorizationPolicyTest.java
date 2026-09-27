package com.soulsoftworks.sockbowlgame.service.authorization;

import com.soulsoftworks.sockbowlgame.controller.exception.UserBannedException;
import com.soulsoftworks.sockbowlgame.model.security.AuthenticatedUser;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.state.Player;
import com.soulsoftworks.sockbowlgame.service.BanService;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GameAuthorizationPolicyTest {

    private static AuthenticatedUser user() {
        // Holds the "game:host" permission authority (raw realm role name, no
        // ROLE_ prefix - mirrors what the Keycloak JWT converter now emits).
        return AuthenticatedUser.of("kc-user", "alice", "alice@example.com", List.of("game:host"));
    }

    private static AuthenticatedUser userWithoutGameHost() {
        return AuthenticatedUser.of("kc-user2", "bob", "bob@example.com", List.of());
    }

    private static AuthenticatedUser admin() {
        return AuthenticatedUser.of("kc-admin", "root", "root@example.com", List.of("ROLE_admin"));
    }

    private static AuthenticatedUser userWithBanAuthority() {
        return AuthenticatedUser.of("kc-mod", "moderator", "mod@example.com", List.of("user:ban"));
    }

    /* -------------------- canCreateGame -------------------- */

    @Test
    void guestModeAllowsAnyoneToCreate() {
        GameAuthorizationPolicy policy = new GameAuthorizationPolicy(false, null);
        assertTrue(policy.canCreateGame(AuthenticatedUser.guest()));
        assertTrue(policy.canCreateGame(user()));
    }

    @Test
    void authEnabledStillAllowsGuestCreate() {
        // Auth is additive: enabling it never removes hosting from guests.
        GameAuthorizationPolicy policy = new GameAuthorizationPolicy(true, null);
        assertTrue(policy.canCreateGame(AuthenticatedUser.guest()));
    }

    @Test
    void authEnabledRequiresGameHostForSignedInUsers() {
        // D1 / AUTH-19: game:host is enforced for authenticated hosts. Every
        // tier gets it through the player composite, so this only bites when
        // the permission has been revoked.
        GameAuthorizationPolicy policy = new GameAuthorizationPolicy(true, null);
        assertTrue(policy.canCreateGame(user()));
        assertFalse(policy.canCreateGame(userWithoutGameHost()));
        // The admin role alone (without its composite expansion) is not game:host.
        assertFalse(policy.canCreateGame(admin()));
        assertTrue(policy.canCreateGame(AuthenticatedUser.of(
                "kc-admin", "root", null, List.of("ROLE_admin", "game:host", "admin:access"))));
    }

    @Test
    void serviceIdentityCannotCreateWhenAuthEnabled() {
        GameAuthorizationPolicy policy = new GameAuthorizationPolicy(true, null);
        // Even a service token that somehow held game:host is not a user.
        assertFalse(policy.canCreateGame(serviceIdentity()));
        assertFalse(policy.canCreateGame(
                AuthenticatedUser.service("svc", List.of("packet:read", "game:host"))));
    }

    @Test
    void authDisabledAllowsEveryoneIncludingServiceAndUsersWithoutGameHost() {
        GameAuthorizationPolicy policy = new GameAuthorizationPolicy(false, null);
        assertTrue(policy.canCreateGame(null));
        assertTrue(policy.canCreateGame(userWithoutGameHost()));
        assertTrue(policy.canCreateGame(serviceIdentity()));
    }

    @Test
    void canCreateGameMatrixWithBans() {
        BanService banService = mock(BanService.class);
        when(banService.isBanned("kc-user")).thenReturn(true);
        GameAuthorizationPolicy policy = new GameAuthorizationPolicy(true, banService);
        assertTrue(policy.canCreateGame(AuthenticatedUser.guest()));
        assertTrue(policy.canCreateGame(null));
        assertFalse(policy.canCreateGame(user()));           // banned, holds game:host
        assertFalse(policy.canCreateGame(userWithoutGameHost()));
        assertFalse(policy.canCreateGame(serviceIdentity()));
    }

    /* -------------------- identity resolution -------------------- */

    private static AuthenticatedUser serviceIdentity() {
        return AuthenticatedUser.service("svc-sub", List.of("packet:read"));
    }

    private static Jwt token(String azp, String clientId, String... roles) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none").subject("sub-1")
                .claim("realm_access", Map.of("roles", List.of(roles)));
        if (azp != null) {
            b.claim("azp", azp);
        }
        if (clientId != null) {
            b.claim("client_id", clientId);
        }
        return b.build();
    }

    @Test
    void identityOfRecognisesTheConfiguredServiceClient() {
        GameAuthorizationPolicy policy = new GameAuthorizationPolicy(true, null, "my-backend");

        AuthenticatedUser svc = policy.identityOf(token("my-backend", null, "packet:read"));
        assertTrue(svc.isService());
        assertFalse(svc.isUser());
        assertTrue(policy.isServiceIdentity(svc));
        assertTrue(policy.isServiceToken(token(null, "my-backend")));

        AuthenticatedUser user = policy.identityOf(token("sockbowl-game", null, "player", "game:host"));
        assertFalse(user.isService());
        assertTrue(user.isUser());
        assertTrue(user.getAuthorities().contains("game:host"));
        // azp wins over client_id when both are present.
        assertFalse(policy.isServiceToken(token("sockbowl-game", "my-backend")));
        // The default id is not special once another is configured.
        assertFalse(policy.identityOf(token("sockbowl-game-backend", null)).isService());
    }

    @Test
    void identityOfNullTokenIsGuest() {
        GameAuthorizationPolicy policy = new GameAuthorizationPolicy(true, null);
        AuthenticatedUser guest = policy.identityOf(null);
        assertTrue(guest.isGuest());
        assertFalse(guest.isService());
        assertFalse(guest.isUser());
        assertFalse(policy.isServiceToken(null));
    }

    @Test
    void defaultServiceClientIdIsSockbowlGameBackend() {
        GameAuthorizationPolicy policy = new GameAuthorizationPolicy(true, null);
        assertTrue(policy.identityOf(token("sockbowl-game-backend", null, "packet:read")).isService());
        assertTrue(AuthenticatedUser.fromJwt(token("sockbowl-game-backend", null)).isService());
    }

    @Test
    void bannedUserWithGameHostAuthorityStillCannotCreate() {
        BanService banService = mock(BanService.class);
        when(banService.isBanned("kc-user")).thenReturn(true);
        GameAuthorizationPolicy policy = new GameAuthorizationPolicy(true, banService);
        // Ban check precedes the authority check, even though this user holds game:host.
        assertFalse(policy.canCreateGame(user()));
    }

    /* -------------------- bans -------------------- */

    @Test
    void ensureNotBannedThrowsForBannedUser() {
        BanService banService = mock(BanService.class);
        when(banService.isBanned("kc-user")).thenReturn(true);
        GameAuthorizationPolicy policy = new GameAuthorizationPolicy(true, banService);
        assertThrows(UserBannedException.class, () -> policy.ensureNotBanned(user()));
    }

    @Test
    void ensureNotBannedNoOpForGuestAndUnbanned() {
        BanService banService = mock(BanService.class);
        when(banService.isBanned("kc-user")).thenReturn(false);
        GameAuthorizationPolicy policy = new GameAuthorizationPolicy(true, banService);
        assertDoesNotThrow(() -> policy.ensureNotBanned(user()));
        assertDoesNotThrow(() -> policy.ensureNotBanned(AuthenticatedUser.guest()));
    }

    @Test
    void noBanServiceMeansNoOneIsBanned() {
        GameAuthorizationPolicy policy = new GameAuthorizationPolicy(true, null);
        assertFalse(policy.isBanned(user()));
        assertDoesNotThrow(() -> policy.ensureNotBanned(user()));
    }

    /* -------------------- admin -------------------- */

    @Test
    void adminCapabilityRequiresAdminRole() {
        GameAuthorizationPolicy policy = new GameAuthorizationPolicy(true, null);
        assertTrue(policy.isAdmin(admin()));
        assertFalse(policy.isAdmin(user()));
        assertFalse(policy.isAdmin(AuthenticatedUser.guest()));
    }

    /* -------------------- canManageBans -------------------- */

    @Test
    void canManageBansRequiresUserBanAuthority() {
        GameAuthorizationPolicy policy = new GameAuthorizationPolicy(true, null);
        assertTrue(policy.canManageBans(userWithBanAuthority()));
        // Holding the admin role alone no longer implies user:ban.
        assertFalse(policy.canManageBans(admin()));
        assertFalse(policy.canManageBans(user()));
        assertFalse(policy.canManageBans(AuthenticatedUser.guest()));
    }

    /* -------------------- session ownership -------------------- */

    @Test
    void authenticatedOwnerResolvedByKeycloakSubject() {
        GameAuthorizationPolicy policy = new GameAuthorizationPolicy(true, null);

        Player owner = Player.builder().playerId("p1").keycloakId("kc-user").build();
        Player other = Player.builder().playerId("p2").keycloakId("kc-other").build();
        GameSession session = mock(GameSession.class);
        when(session.getGameOwnerId()).thenReturn("kc-user");
        when(session.getPlayerById("p1")).thenReturn(owner);
        when(session.getPlayerById("p2")).thenReturn(other);

        assertTrue(policy.isSessionOwner(session, "p1"));
        // Different authenticated user cannot assume ownership.
        assertFalse(policy.isSessionOwner(session, "p2"));
    }

    @Test
    void guestSessionFallsBackToInMemoryOwnerFlag() {
        GameAuthorizationPolicy policy = new GameAuthorizationPolicy(false, null);

        GameSession session = mock(GameSession.class);
        when(session.getGameOwnerId()).thenReturn(null);
        when(session.isPlayerGameOwner("p1")).thenReturn(true);
        when(session.isPlayerGameOwner("p2")).thenReturn(false);

        assertTrue(policy.isSessionOwner(session, "p1"));
        assertFalse(policy.isSessionOwner(session, "p2"));
    }

    @Test
    void nonOwnerCannotConfigureButCanChangeOwnTeam() {
        GameAuthorizationPolicy policy = new GameAuthorizationPolicy(false, null);

        GameSession session = mock(GameSession.class);
        when(session.getGameOwnerId()).thenReturn(null);
        when(session.isPlayerGameOwner("owner")).thenReturn(true);
        when(session.isPlayerGameOwner("p2")).thenReturn(false);

        assertFalse(policy.canConfigureSession(session, "p2"));
        assertTrue(policy.canChangeTeam(session, "p2", "p2"));
        assertFalse(policy.canChangeTeam(session, "p2", "owner"));
        assertTrue(policy.canChangeTeam(session, "owner", "p2"));
    }

    @Test
    void proctorManagementAllowsSelfClaimWhenNoProctorSet() {
        GameAuthorizationPolicy policy = new GameAuthorizationPolicy(false, null);

        GameSession session = mock(GameSession.class);
        when(session.getGameOwnerId()).thenReturn(null);
        when(session.isPlayerGameOwner("owner")).thenReturn(true);
        when(session.isPlayerGameOwner("p2")).thenReturn(false);
        when(session.getProctor()).thenReturn(null);

        assertTrue(policy.canManageProctor(session, "owner", "p2"));
        assertTrue(policy.canManageProctor(session, "p2", "p2"));
        assertFalse(policy.canManageProctor(session, "p2", "owner"));
    }
}
