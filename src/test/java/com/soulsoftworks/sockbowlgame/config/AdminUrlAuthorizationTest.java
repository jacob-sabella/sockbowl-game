package com.soulsoftworks.sockbowlgame.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Focused MockMvc slice proving the URL-level authorization rules in
 * {@link SecurityConfig} for the {@code /api/v1/admin/**} space, without
 * booting the full application context.
 *
 * <p>This regression-tests the bug where {@code /api/v1/admin/**} was
 * gated by {@code hasRole("admin")} at the URL layer, which rejected
 * moderators (holders of the fine-grained {@code user:ban} authority but
 * not the {@code admin} role) before the request ever reached
 * {@code AdminBanController}'s method-level {@code @PreAuthorize}. The
 * existing {@code GameAuthorizationPolicyTest} tests the policy directly
 * and never exercises the filter chain, so it could not catch this class
 * of bug.
 *
 * <p>{@code @ContextConfiguration(classes = TestApp.class)} pins the slice
 * to a minimal {@code @SpringBootConfiguration} instead of letting Spring
 * Boot auto-detect {@code SockbowlGameApplication}. The real application
 * class carries {@code @EnableRedisDocumentRepositories}, which would drag
 * a full Redis repository/message-listener infrastructure into this
 * MockMvc-only slice.
 */
@WebMvcTest(controllers = AdminUrlProbeController.class, properties = "sockbowl.auth.enabled=true")
@Import({SecurityConfig.class, AdminUrlProbeController.class})
@ContextConfiguration(classes = AdminUrlAuthorizationTest.TestApp.class)
class AdminUrlAuthorizationTest {

    @SpringBootConfiguration
    static class TestApp {
    }

    @Autowired
    private MockMvc mvc;

    // SecurityConfig configures oauth2Login(), which requires a
    // ClientRegistrationRepository bean to be present in the context even
    // though these tests only exercise the resource-server (JWT) path.
    @MockitoBean
    private ClientRegistrationRepository clientRegistrationRepository;

    // Overrides SecurityConfig's real jwtDecoder() bean, which eagerly reaches
    // out to an issuer-uri that isn't configured in this slice test.
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void moderator_with_user_ban_can_reach_bans() throws Exception {
        mvc.perform(get("/api/v1/admin/bans").with(jwt().authorities(new SimpleGrantedAuthority("user:ban"))))
                .andExpect(status().isOk());
    }

    @Test
    void user_ban_cannot_reach_admin_console() throws Exception {
        mvc.perform(get("/api/v1/admin/console").with(jwt().authorities(new SimpleGrantedAuthority("user:ban"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_access_can_reach_console() throws Exception {
        mvc.perform(get("/api/v1/admin/console").with(jwt().authorities(new SimpleGrantedAuthority("admin:access"))))
                .andExpect(status().isOk());
    }

    @Test
    void unauthenticated_is_401() throws Exception {
        mvc.perform(get("/api/v1/admin/bans")).andExpect(status().isUnauthorized());
    }
}
