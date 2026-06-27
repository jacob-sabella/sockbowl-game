package com.soulsoftworks.sockbowlgame.service.authorization;

import com.soulsoftworks.sockbowlgame.controller.exception.UserBannedException;
import com.soulsoftworks.sockbowlgame.model.security.AuthenticatedUser;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.state.Player;
import com.soulsoftworks.sockbowlgame.service.BanService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GameAuthorizationPolicyTest {

    private static AuthenticatedUser user() {
        return AuthenticatedUser.of("kc-user", "alice", "alice@example.com", List.of("ROLE_user"));
    }

    private static AuthenticatedUser admin() {
        return AuthenticatedUser.of("kc-admin", "root", "root@example.com", List.of("ROLE_admin"));
    }

    /* -------------------- canCreateGame -------------------- */

    @Test
    void guestModeAllowsAnyoneToCreate() {
        GameAuthorizationPolicy policy = new GameAuthorizationPolicy(false, null);
        assertTrue(policy.canCreateGame(AuthenticatedUser.guest()));
        assertTrue(policy.canCreateGame(user()));
    }

    @Test
    void authEnabledDeniesGuestCreate() {
        GameAuthorizationPolicy policy = new GameAuthorizationPolicy(true, null);
        assertFalse(policy.canCreateGame(AuthenticatedUser.guest()));
    }

    @Test
    void authEnabledAllowsUserRoleCreate() {
        GameAuthorizationPolicy policy = new GameAuthorizationPolicy(true, null);
        assertTrue(policy.canCreateGame(user()));
        assertTrue(policy.canCreateGame(admin()));
    }

    @Test
    void bannedUserCannotCreate() {
        BanService banService = mock(BanService.class);
        when(banService.isBanned("kc-user")).thenReturn(true);
        GameAuthorizationPolicy policy = new GameAuthorizationPolicy(true, banService);
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
        assertTrue(policy.canManageBans(admin()));
        assertFalse(policy.isAdmin(user()));
        assertFalse(policy.canManageBans(user()));
        assertFalse(policy.isAdmin(AuthenticatedUser.guest()));
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
