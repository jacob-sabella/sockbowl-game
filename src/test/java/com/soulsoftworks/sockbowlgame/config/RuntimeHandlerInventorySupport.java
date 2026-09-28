package com.soulsoftworks.sockbowlgame.config;

import com.soulsoftworks.sockbowlgame.support.StompMappingClassification;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.simp.SimpMessageMappingInfo;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.annotation.support.SimpAnnotationMethodMessageHandler;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R4-G-02 (closes R3-G-INV): the STOMP and REST inventories are checked
 * against the handler registries of the running application, not a classpath
 * scan. {@code StompMessageMappingInventoryTest} and
 * {@code SecurityConfigHttpMatrixTest} enumerate handlers by reflection over
 * {@code @Controller} classes; a handler that Spring registers some other way
 * (a {@code @MessageMapping} on an inherited or interface method, a
 * {@code @RequestMapping} meta-annotation, a controller in a package outside
 * the scan, a bean registered programmatically) would escape them. Here the
 * source of truth is what Spring actually dispatches to:
 * {@link SimpAnnotationMethodMessageHandler#getHandlerMethods()} and
 * {@link RequestMappingHandlerMapping#getHandlerMethods()}. Any handler that is
 * not listed fails the build.
 */
abstract class RuntimeHandlerInventorySupport {

    /**
     * Every REST handler the auth-on application registers, as
     * {@code "METHOD /path"}. {@code ANY} means the mapping names no HTTP
     * method. A new endpoint must be added here, and to
     * {@link SecurityConfigHttpMatrixTest#matrix()}.
     */
    static final Set<String> EXPECTED_REST_HANDLERS = Set.of(
            "POST /api/v1/session/create-new-game-session",
            "POST /api/v1/session/join-game-session-by-code",
            "POST /api/v1/session/join-game-session-authenticated",
            "GET /api/v1/user/profile",
            "GET /api/v1/user/stats",
            "GET /api/v1/user/history",
            "GET /api/v1/user/used-questions",
            "POST /api/v1/user/used-questions",
            "GET /api/v1/auth/me",
            "GET /api/v1/auth/status",
            "GET /api/v1/admin/bans",
            "POST /api/v1/admin/bans",
            "DELETE /api/v1/admin/bans/{banId}",
            "ANY /error");

    /**
     * Spring Boot's own error controller: it only renders errors the filter
     * chain and the other handlers produce, and SecurityConfig permits
     * {@code /error} so an error response is never itself turned into a 401.
     */
    static final Set<String> FRAMEWORK_REST_HANDLERS = Set.of("ANY /error");

    /**
     * The REST handlers that exist only with auth on (their controllers are
     * {@code @ConditionalOnProperty}). The authenticated join stays mapped
     * with auth off; it then answers 404 itself.
     */
    static final Set<String> AUTH_ONLY_REST_HANDLERS = Set.of(
            "GET /api/v1/user/profile",
            "GET /api/v1/user/stats",
            "GET /api/v1/user/history",
            "GET /api/v1/user/used-questions",
            "POST /api/v1/user/used-questions",
            "GET /api/v1/auth/me",
            "GET /api/v1/auth/status",
            "GET /api/v1/admin/bans",
            "POST /api/v1/admin/bans",
            "DELETE /api/v1/admin/bans/{banId}");

    @Autowired
    SimpAnnotationMethodMessageHandler stompHandlers;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping restHandlers;

    /** Every registered SEND ({@code @MessageMapping}) destination, with the application prefix. */
    Set<String> registeredSendDestinations() {
        return registeredStompDestinations(SimpMessageType.MESSAGE);
    }

    /** Every registered SUBSCRIBE ({@code @SubscribeMapping}) destination, with the application prefix. */
    Set<String> registeredSubscribeDestinations() {
        return registeredStompDestinations(SimpMessageType.SUBSCRIBE);
    }

    private Set<String> registeredStompDestinations(SimpMessageType type) {
        Set<String> destinations = new TreeSet<>();
        for (Map.Entry<SimpMessageMappingInfo, org.springframework.messaging.handler.HandlerMethod> entry : stompHandlers.getHandlerMethods().entrySet()) {
            SimpMessageMappingInfo info = entry.getKey();
            SimpMessageType handled = info.getMessageTypeMessageCondition().getMessageType();
            assertThat(handled).as("%s handles only SEND or SUBSCRIBE", entry.getValue())
                    .isIn(SimpMessageType.MESSAGE, SimpMessageType.SUBSCRIBE);
            if (handled != type) {
                continue;
            }
            Set<String> patterns = info.getDestinationConditions().getPatterns();
            assertThat(patterns).as("%s has an explicit destination", entry.getValue()).isNotEmpty();
            for (String pattern : patterns) {
                String destination = withAppPrefix(pattern);
                assertThat(destinations.add(destination)).as("duplicate STOMP handler %s", destination).isTrue();
            }
        }
        return destinations;
    }

    private String withAppPrefix(String pattern) {
        if (pattern.startsWith(WebSocketConfig.APP_PREFIX + "/")) {
            return pattern;
        }
        return (WebSocketConfig.APP_PREFIX + "/" + pattern).replaceAll("/+", "/");
    }

    /** Every registered REST handler, as {@code "METHOD /path"}. */
    Set<String> registeredRestHandlers() {
        Set<String> handlers = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : restHandlers.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            Set<String> patterns = info.getPatternValues();
            assertThat(patterns).as("%s has an explicit path", entry.getValue()).isNotEmpty();
            Set<String> methods = new TreeSet<>();
            info.getMethodsCondition().getMethods().forEach(m -> methods.add(m.name()));
            if (methods.isEmpty()) {
                methods.add("ANY");
            }
            for (String pattern : patterns) {
                for (String method : methods) {
                    // BasicErrorController maps /error twice (JSON and HTML), by produces only.
                    handlers.add(method + " " + pattern);
                }
            }
        }
        return handlers;
    }

    @Test
    void stompRegistryIsExactlyTheClassifiedInventory() {
        assertThat(stompHandlers.getDestinationPrefixes()).containsExactly(WebSocketConfig.APP_PREFIX + "/");
        Set<String> registered = registeredSendDestinations();
        assertThat(registered).as("every registered @MessageMapping is classified in StompMappingClassification")
                .isSubsetOf(StompMappingClassification.ALL);
        assertThat(registered).as("no stale StompMappingClassification entry")
                .containsExactlyInAnyOrderElementsOf(StompMappingClassification.ALL);
        assertThat(registered).allSatisfy(d -> assertThat(d).startsWith("/app/").doesNotContain("*", "{", ".."));
    }

    @Test
    void noSubscribeHandlerIsRegistered() {
        assertThat(registeredSubscribeDestinations())
                .as("a @SubscribeMapping must be inventoried and authorization-tested before it ships")
                .isEmpty();
    }
}
