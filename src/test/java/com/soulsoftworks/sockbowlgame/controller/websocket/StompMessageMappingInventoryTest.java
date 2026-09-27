package com.soulsoftworks.sockbowlgame.controller.websocket;

import com.soulsoftworks.sockbowlgame.config.WebSocketConfig;
import com.soulsoftworks.sockbowlgame.config.WebSocketLimitsProperties;
import com.soulsoftworks.sockbowlgame.websocket.ClientIpHandshakeInterceptor;
import com.soulsoftworks.sockbowlgame.controller.resolver.GameSessionInjectionResolver;
import com.soulsoftworks.sockbowlgame.model.request.GameSessionInjection;
import com.soulsoftworks.sockbowlgame.model.request.PlayerIdentifiers;
import com.soulsoftworks.sockbowlgame.model.security.AuthenticatedUser;
import com.soulsoftworks.sockbowlgame.model.socket.in.SockbowlInMessage;
import com.soulsoftworks.sockbowlgame.model.state.GameSession;
import com.soulsoftworks.sockbowlgame.model.state.GameSettings;
import com.soulsoftworks.sockbowlgame.security.stomp.StompInboundInterceptor;
import com.soulsoftworks.sockbowlgame.security.stomp.StompPrincipal;
import com.soulsoftworks.sockbowlgame.service.MessageService;
import com.soulsoftworks.sockbowlgame.support.StompMappingClassification;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.stereotype.Controller;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Inventory of every STOMP {@code @MessageMapping} (plan m2-auth WP-G2, critic
 * note 5; the "every socket destination" proof for M2's Done-when).
 *
 * <ul>
 *   <li>every mapping resolves under the {@code /app} application prefix, the
 *       only prefix clients may SEND to, and the broker/user prefixes are
 *       {@code /queue} and {@code /user};</li>
 *   <li>every mapping that produces a game message takes the principal-derived
 *       {@link GameSessionInjection} and <b>overwrites every identity field of
 *       the message body from it</b> ({@code stampOrigin}): a client that puts
 *       another player's id, another game's id, a Keycloak subject, or
 *       {@code packet:manage-any} in the STOMP body gets its values replaced by
 *       the authenticated principal's (null / empty for guests), and they
 *       survive the Kafka JSON round trip the consumer sees.</li>
 * </ul>
 * WP-G4 adds the classification check: every mapping is in exactly one of
 * {@link StompMappingClassification}'s {@code OWNER_GATED}, {@code PROCTOR_GATED}
 * and {@code ANY_PLAYER} sets, which the in-session authorization tests
 * ({@code SessionOwnershipAuthTest}, {@code InSessionRoleMatrixTest}) iterate,
 * so a new mapping that nobody classified (and so nobody authorization-tested)
 * fails the build.
 */
class StompMessageMappingInventoryTest {

    private static final String WEBSOCKET_PACKAGE = "com.soulsoftworks.sockbowlgame.controller.websocket";

    /** Every client-reachable STOMP destination. A new mapping must be added here (and classified by G3/G4). */
    static final Set<String> EXPECTED_DESTINATIONS = Set.of(
            "/app/heartbeat",
            "/app/game/config/update-player-team",
            "/app/game/config/set-match-packet",
            "/app/game/config/set-proctor",
            "/app/game/config/get-game",
            "/app/game/config/update-game-settings",
            "/app/game/progression/start-match",
            "/app/game/progression/end-match",
            "/app/game/answer-outcome",
            "/app/game/player-incoming-buzz",
            "/app/game/submit-answer",
            "/app/game/timeout-round",
            "/app/game/finished-reading",
            "/app/game/advance-round",
            "/app/game/bonus-part-outcome",
            "/app/game/finished-reading-bonus-preamble",
            "/app/game/finished-reading-bonus-part",
            "/app/game/timeout-bonus-part",
            "/app/game/start-bonus");

    /** Mappings that take no player context and produce no game message. */
    private static final Set<String> STATELESS_DESTINATIONS = Set.of("/app/heartbeat");

    private static final String REAL_GAME = "game-real";
    private static final String REAL_PLAYER = "player-real";
    private static final String REAL_SUB = "kc-real";

    record Mapping(String destination, Class<?> controller, Method method) {
    }

    static List<Mapping> allMappings() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        List<Mapping> mappings = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(WEBSOCKET_PACKAGE)) {
            Class<?> controller = Class.forName(candidate.getBeanClassName());
            MessageMapping classMapping = controller.getAnnotation(MessageMapping.class);
            String[] prefixes = classMapping == null || classMapping.value().length == 0
                    ? new String[]{""} : classMapping.value();
            for (Method method : controller.getDeclaredMethods()) {
                MessageMapping methodMapping = method.getAnnotation(MessageMapping.class);
                if (methodMapping == null) {
                    continue;
                }
                assertThat(methodMapping.value()).as("%s has an explicit destination", method).isNotEmpty();
                for (String prefix : prefixes) {
                    for (String value : methodMapping.value()) {
                        mappings.add(new Mapping(destination(prefix, value), controller, method));
                    }
                }
            }
        }
        return mappings;
    }

    private static String destination(String classPath, String methodPath) {
        String joined = "/" + classPath + "/" + methodPath;
        return WebSocketConfig.APP_PREFIX + joined.replaceAll("/+", "/");
    }

    @Test
    void brokerPrefixesAreQueueAppAndUser() {
        WebSocketConfig config = new WebSocketConfig(mock(GameSessionInjectionResolver.class),
                mock(StompInboundInterceptor.class), mock(ClientIpHandshakeInterceptor.class),
                new WebSocketLimitsProperties());
        MessageBrokerRegistry registry = mock(MessageBrokerRegistry.class);
        config.configureMessageBroker(registry);

        verify(registry).enableSimpleBroker("/queue");
        verify(registry).setApplicationDestinationPrefixes("/app");
        verify(registry).setUserDestinationPrefix("/user");
        verify(registry, times(1)).enableSimpleBroker(any(String[].class));
    }

    @Test
    void everyMappingIsUnderAppAndInventoried() throws Exception {
        List<Mapping> mappings = allMappings();
        Set<String> destinations = new TreeSet<>();
        for (Mapping mapping : mappings) {
            assertThat(mapping.destination()).startsWith("/app/");
            assertThat(mapping.destination()).doesNotContain("..", "*", "{");
            assertThat(destinations.add(mapping.destination())).as("duplicate %s", mapping.destination()).isTrue();
        }
        assertThat(destinations).containsExactlyInAnyOrderElementsOf(EXPECTED_DESTINATIONS);
        assertThat(destinations).hasSize(19);
    }

    @Test
    void everyMappingIsClassifiedExactlyOnce() throws Exception {
        List<Mapping> mappings = allMappings();
        Set<String> discovered = new TreeSet<>();
        for (Mapping mapping : mappings) {
            String destination = mapping.destination();
            discovered.add(destination);
            int classes = (StompMappingClassification.OWNER_GATED.contains(destination) ? 1 : 0)
                    + (StompMappingClassification.PROCTOR_GATED.contains(destination) ? 1 : 0)
                    + (StompMappingClassification.ANY_PLAYER.contains(destination) ? 1 : 0);
            assertThat(classes)
                    .as("%s (%s.%s) must be in exactly one of OWNER_GATED, PROCTOR_GATED, ANY_PLAYER "
                                    + "in StompMappingClassification", destination,
                            mapping.controller().getSimpleName(), mapping.method().getName())
                    .isEqualTo(1);
            assertThat(StompMappingClassification.classOf(destination)).as(destination).isNotNull();
        }
        // No stale entries: the classification lists exactly the real mappings.
        assertThat(StompMappingClassification.ALL).containsExactlyInAnyOrderElementsOf(discovered);
        assertThat(StompMappingClassification.PROCTOR_IN_PROCTORED_MODES)
                .isSubsetOf(StompMappingClassification.OWNER_GATED);
    }

    @Test
    void classificationMessageTypesMatchWhatEachMappingProduces() throws Exception {
        Map<String, Class<?>> produced = new LinkedHashMap<>();
        for (Mapping mapping : allMappings()) {
            if (!STATELESS_DESTINATIONS.contains(mapping.destination())) {
                produced.put(mapping.destination(),
                        invoke(mapping, StompPrincipal.guest(REAL_GAME, REAL_PLAYER)).getClass());
            }
        }
        // The in-session tests build each destination's message from
        // MESSAGE_TYPES, so it must be the type that destination really produces.
        assertThat(new LinkedHashMap<String, Class<?>>(StompMappingClassification.MESSAGE_TYPES))
                .containsExactlyInAnyOrderEntriesOf(produced);
    }

    @Test
    void onlyStatelessMappingsSkipTheInjection() throws Exception {
        for (Mapping mapping : allMappings()) {
            boolean takesInjection = List.of(mapping.method().getParameterTypes()).contains(GameSessionInjection.class);
            if (STATELESS_DESTINATIONS.contains(mapping.destination())) {
                assertThat(takesInjection).as(mapping.destination()).isFalse();
                assertThat(List.of(mapping.method().getParameterTypes()))
                        .as("%s takes no game message", mapping.destination())
                        .noneMatch(SockbowlInMessage.class::isAssignableFrom);
            } else {
                assertThat(takesInjection).as("%s must take GameSessionInjection", mapping.destination()).isTrue();
            }
        }
    }

    @Test
    void signedInSenderIdentityIsStampedOverForgedBodyFields() throws Exception {
        StompPrincipal principal = StompPrincipal.user(REAL_GAME, REAL_PLAYER, REAL_SUB,
                Set.of("game:host", "packet:create"), null);
        assertEveryMappingStamps(principal, REAL_SUB, Set.of("game:host", "packet:create"));
    }

    @Test
    void guestSenderGetsNullSubjectAndNoAuthoritiesWhateverTheBodySays() throws Exception {
        assertEveryMappingStamps(StompPrincipal.guest(REAL_GAME, REAL_PLAYER), null, Set.of());
    }

    private void assertEveryMappingStamps(StompPrincipal principal, String expectedSub,
                                          Set<String> expectedAuthorities) throws Exception {
        Map<String, SockbowlInMessage> produced = new LinkedHashMap<>();
        for (Mapping mapping : allMappings()) {
            if (STATELESS_DESTINATIONS.contains(mapping.destination())) {
                continue;
            }
            SockbowlInMessage message = invoke(mapping, principal);
            produced.put(mapping.destination(), message);

            assertThat(message.getOriginatingPlayerId()).as(mapping.destination()).isEqualTo(REAL_PLAYER);
            assertThat(message.getGameSessionId()).as(mapping.destination()).isEqualTo(REAL_GAME);
            assertThat(message.getOriginatingKeycloakId()).as(mapping.destination()).isEqualTo(expectedSub);
            assertThat(message.getOriginatingAuthorities()).as(mapping.destination())
                    .containsExactlyInAnyOrderElementsOf(expectedAuthorities)
                    .doesNotContain("packet:manage-any");
            assertThat(message.getGameSession()).as("%s drops a client-supplied gameSession", mapping.destination())
                    .isNull();

            // What the Kafka consumer (and so WP-G4's SetMatchPacket check) actually sees.
            SockbowlInMessage consumed = kafkaRoundTrip(message);
            assertThat(consumed.getClass()).isEqualTo(message.getClass());
            assertThat(consumed.getOriginatingPlayerId()).isEqualTo(REAL_PLAYER);
            assertThat(consumed.getGameSessionId()).isEqualTo(REAL_GAME);
            assertThat(consumed.getOriginatingKeycloakId()).as(mapping.destination()).isEqualTo(expectedSub);
            assertThat(consumed.getOriginatingAuthorities() == null ? Set.of() : consumed.getOriginatingAuthorities())
                    .as(mapping.destination()).containsExactlyInAnyOrderElementsOf(expectedAuthorities);
        }
        assertThat(produced).hasSize(EXPECTED_DESTINATIONS.size() - STATELESS_DESTINATIONS.size());
    }

    /** Invoke one mapping with the principal's injection and a body full of forged identity fields. */
    private SockbowlInMessage invoke(Mapping mapping, StompPrincipal principal) throws Exception {
        MessageService messageService = mock(MessageService.class);
        Object controller = instantiate(mapping.controller(), messageService);

        GameSessionInjection injection = new GameSessionInjection(
                new PlayerIdentifiers(principal.getPlayerSessionId(), null),
                principal.getGameSessionId(),
                GameSession.builder().id(principal.getGameSessionId()).joinCode("REAL01").gameSettings(new GameSettings()).build(),
                principal.isGuest() ? AuthenticatedUser.guest()
                        : AuthenticatedUser.of(principal.getKeycloakId(), null, null,
                        List.copyOf(principal.getAuthorities())),
                principal);

        Method method = mapping.method();
        Object[] args = new Object[method.getParameterCount()];
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == GameSessionInjection.class) {
                args[i] = injection;
            } else if (SockbowlInMessage.class.isAssignableFrom(types[i])) {
                args[i] = forgedBody(types[i]);
            } else {
                throw new AssertionError("Unexpected parameter " + types[i] + " on " + method);
            }
        }
        method.setAccessible(true);
        method.invoke(controller, args);

        ArgumentCaptor<SockbowlInMessage> captor = ArgumentCaptor.forClass(SockbowlInMessage.class);
        verify(messageService, times(1).description(mapping.destination() + " produces exactly one message"))
                .sendMessage(captor.capture());
        return captor.getValue();
    }

    private static SockbowlInMessage forgedBody(Class<?> type) throws Exception {
        assertThat(Modifier.isAbstract(type.getModifiers())).isFalse();
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        SockbowlInMessage body = (SockbowlInMessage) constructor.newInstance();
        body.setOriginatingPlayerId("forged-player");
        body.setGameSessionId("forged-game");
        body.setGameSession(GameSession.builder().id("forged-game").joinCode("FORGED").gameSettings(new GameSettings()).build());
        body.setOriginatingKeycloakId("kc-forged-admin");
        body.setOriginatingAuthorities(new HashSet<>(Set.of("packet:manage-any", "admin:access")));
        return body;
    }

    private static Object instantiate(Class<?> controller, MessageService messageService) throws Exception {
        Constructor<?> constructor = controller.getDeclaredConstructors()[0];
        Object[] args = new Object[constructor.getParameterCount()];
        Class<?>[] types = constructor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            args[i] = types[i] == MessageService.class ? messageService : mock(types[i]);
        }
        constructor.setAccessible(true);
        return constructor.newInstance(args);
    }

    /** Serialize and deserialize exactly as the game topic's producer and consumer do (KafkaConfig). */
    private static SockbowlInMessage kafkaRoundTrip(SockbowlInMessage message) {
        RecordHeaders headers = new RecordHeaders();
        try (JacksonJsonSerializer<SockbowlInMessage> serializer = new JacksonJsonSerializer<>();
             JacksonJsonDeserializer<SockbowlInMessage> deserializer =
                     new JacksonJsonDeserializer<>(SockbowlInMessage.class)) {
            deserializer.addTrustedPackages("com.soulsoftworks.sockbowlgame");
            byte[] bytes = serializer.serialize("game-topic", headers, message);
            return deserializer.deserialize("game-topic", headers, bytes);
        }
    }
}
