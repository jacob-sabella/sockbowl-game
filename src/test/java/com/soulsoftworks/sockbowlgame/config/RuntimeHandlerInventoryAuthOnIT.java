package com.soulsoftworks.sockbowlgame.config;

import com.soulsoftworks.sockbowlgame.SockbowlGameApplication;
import com.soulsoftworks.sockbowlgame.repository.UserGameHistoryRepository;
import com.soulsoftworks.sockbowlgame.repository.UserRepository;
import com.soulsoftworks.sockbowlgame.service.BanService;
import com.soulsoftworks.sockbowlgame.service.UserService;
import com.soulsoftworks.sockbowlgame.service.UserUsedQuestionService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R4-G-02 with {@code sockbowl.auth.enabled=true}: every STOMP and REST
 * handler the running application registers is inventoried, classified and
 * in the HTTP authorization matrix. JPA collaborators and the JWT decoder are
 * mocked as in {@code StompSecurityAuthOnIT}.
 */
@SpringBootTest(classes = SockbowlGameApplication.class, properties = {
        "sockbowl.auth.enabled=true",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://127.0.0.1:1/realms/sockbowl",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/realms/sockbowl/protocol/openid-connect/certs",
        "spring.security.oauth2.client.provider.keycloak.token-uri=http://127.0.0.1:1/realms/sockbowl/protocol/openid-connect/token",
        "spring.security.oauth2.client.registration.questions-svc.provider=keycloak",
        "spring.security.oauth2.client.registration.questions-svc.client-id=sockbowl-game-backend",
        "spring.security.oauth2.client.registration.questions-svc.client-secret=test-secret",
        "spring.security.oauth2.client.registration.questions-svc.authorization-grant-type=client_credentials",
        "sockbowl.questions.url=http://127.0.0.1:1/"
})
class RuntimeHandlerInventoryAuthOnIT extends RuntimeHandlerInventorySupport {

    @MockitoBean
    JwtDecoder jwtDecoder;
    @MockitoBean
    BanService banService;
    @MockitoBean
    UserService userService;
    @MockitoBean
    UserUsedQuestionService usedQuestionService;
    @MockitoBean
    UserRepository userRepository;
    @MockitoBean
    UserGameHistoryRepository userGameHistoryRepository;

    @Test
    void everyRegisteredRestHandlerIsInventoried() {
        assertThat(registeredRestHandlers()).containsExactlyInAnyOrderElementsOf(EXPECTED_REST_HANDLERS);
    }

    /**
     * Every inventoried REST handler has a row in
     * {@link SecurityConfigHttpMatrixTest#matrix()}, so a new endpoint cannot
     * reach production without its five-caller authorization row.
     */
    @Test
    void everyInventoriedRestHandlerHasAnAuthorizationMatrixRow() {
        Set<String> rows = SecurityConfigHttpMatrixTest.matrix()
                .map(a -> a.get()[0] + " " + normalizePath((String) a.get()[1]))
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> missing = new TreeSet<>(EXPECTED_REST_HANDLERS);
        missing.removeAll(FRAMEWORK_REST_HANDLERS);
        missing.removeAll(rows);
        assertThat(missing).as("REST handlers with no SecurityConfigHttpMatrixTest row").isEmpty();
    }

    /**
     * Maps a concrete {@code matrix()} row path back onto the path-variable
     * template it exercises in {@link RuntimeHandlerInventorySupport#EXPECTED_REST_HANDLERS},
     * e.g. {@code /api/v1/admin/bans/<uuid>} -> {@code /api/v1/admin/bans/{banId}}.
     * {@code /api/v1/admin/usage/global} and {@code /.../events} are literal
     * siblings of the {@code {sub}} route, not instances of it, and must not
     * be rewritten.
     */
    private static String normalizePath(String path) {
        path = path.replaceAll("^/api/v1/admin/bans/[0-9a-f-]{36}$", "/api/v1/admin/bans/{banId}");
        path = path.replaceAll("^/api/v1/admin/bans/ip/[0-9a-f-]{36}$", "/api/v1/admin/bans/ip/{banId}");
        path = path.replaceAll("^/api/v1/admin/usage/(?!global$|events$)[^/]+/quota/[^/]+$",
                "/api/v1/admin/usage/{sub}/quota/{metric}");
        path = path.replaceAll("^/api/v1/admin/usage/(?!global$|events$)[^/]+/reset$",
                "/api/v1/admin/usage/{sub}/reset");
        path = path.replaceAll("^/api/v1/admin/usage/(?!global$|events$)[^/]+$", "/api/v1/admin/usage/{sub}");
        return path;
    }
}
