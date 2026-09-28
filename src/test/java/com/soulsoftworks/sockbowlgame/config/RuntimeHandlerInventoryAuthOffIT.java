package com.soulsoftworks.sockbowlgame.config;

import com.soulsoftworks.sockbowlgame.SockbowlGameApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R4-G-02 with {@code sockbowl.auth.enabled=false} (local dev): the same STOMP
 * inventory, and exactly the inventoried REST handlers that do not need auth.
 */
@SpringBootTest(classes = SockbowlGameApplication.class, properties = "sockbowl.auth.enabled=false")
class RuntimeHandlerInventoryAuthOffIT extends RuntimeHandlerInventorySupport {

    @Test
    void everyRegisteredRestHandlerIsInventoried() {
        Set<String> expected = new TreeSet<>(EXPECTED_REST_HANDLERS);
        expected.removeAll(AUTH_ONLY_REST_HANDLERS);
        assertThat(registeredRestHandlers()).containsExactlyInAnyOrderElementsOf(expected);
    }
}
