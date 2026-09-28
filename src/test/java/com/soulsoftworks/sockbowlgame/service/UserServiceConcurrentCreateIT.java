package com.soulsoftworks.sockbowlgame.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INT1 live gate WP INT1-PROFILE: reproduces, against a real Postgres, the
 * race {@code UserService.findOrCreateUser} lost when the profile page fired
 * its {@code stats} and {@code history} requests in parallel for a user whose
 * row didn't exist yet -- both saw no row, both tried to {@code INSERT}, and
 * the loser's {@link org.springframework.dao.DataIntegrityViolationException}
 * surfaced as a 500 instead of the profile card (admin-usage.spec.ts's
 * {@code provisionUser()} on a freshly started stack).
 *
 * <p>Runs several concurrent {@code findOrCreateUser} calls for the same
 * never-before-seen keycloak id against a real unique constraint: none may
 * throw, all must return the same row, and exactly one row may exist
 * afterward.
 */
@Testcontainers
@SpringBootTest(properties = {
        "sockbowl.auth.enabled=true",
        // The shared test properties exclude JPA; re-enable it against a real Postgres.
        "spring.autoconfigure.exclude=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://127.0.0.1:1/realms/sockbowl",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/realms/sockbowl/protocol/openid-connect/certs",
        "spring.security.oauth2.client.provider.keycloak.token-uri=http://127.0.0.1:1/realms/sockbowl/protocol/openid-connect/token",
        "spring.security.oauth2.client.registration.questions-svc.provider=keycloak",
        "spring.security.oauth2.client.registration.questions-svc.client-id=sockbowl-game-backend",
        "spring.security.oauth2.client.registration.questions-svc.client-secret=test-secret",
        "spring.security.oauth2.client.registration.questions-svc.authorization-grant-type=client_credentials",
        "sockbowl.questions.url=http://127.0.0.1:1/"
})
class UserServiceConcurrentCreateIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
            .withDatabaseName("sockbowl_users");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    UserService userService;

    @Test
    void concurrentFindOrCreateForTheSameNewUserNeverFailsAndCreatesOneRow() throws Exception {
        String keycloakId = "concurrent-new-user";
        String email = "concurrent-new-user@sockbowl.com";
        String name = "Concurrent New User";

        int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Callable<java.util.UUID>> tasks = java.util.stream.IntStream.range(0, callers)
                    .<Callable<java.util.UUID>>mapToObj(i -> () ->
                            userService.findOrCreateUser(keycloakId, email, name).getId())
                    .collect(Collectors.toList());

            List<Future<java.util.UUID>> futures = pool.invokeAll(tasks, 30, TimeUnit.SECONDS);

            // Every call must resolve (none throws) and every call must agree
            // on the same row's id -- exactly one row was created.
            List<java.util.UUID> ids = futures.stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }).collect(Collectors.toList());

            assertThat(ids).hasSize(callers);
            assertThat(ids).allMatch(id -> id.equals(ids.get(0)));
            assertThat(userService.findByKeycloakId(keycloakId)).isPresent();
        } finally {
            pool.shutdownNow();
        }
    }
}
