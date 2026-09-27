package com.soulsoftworks.sockbowlgame.config;

import com.soulsoftworks.sockbowlgame.controller.resolver.GameSessionInjectionResolver;
import com.soulsoftworks.sockbowlgame.security.stomp.SockbowlStompErrorHandler;
import com.soulsoftworks.sockbowlgame.security.stomp.StompInboundInterceptor;
import jakarta.annotation.PreDestroy;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.messaging.handler.invocation.HandlerMethodArgumentResolver;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;

/**
 * STOMP over WebSocket (plan m2-auth section 2.5).
 *
 * <ul>
 *   <li>Broker destinations: {@code /queue/**} only. {@code /user} is the
 *       user-destination prefix (resolved per connection, e.g.
 *       {@code /user/queue/errors}), not a broker prefix.</li>
 *   <li>Application destinations: {@code /app/**}; clients may SEND nowhere else.</li>
 *   <li>Every inbound frame goes through {@link StompInboundInterceptor}
 *       (CONNECT authentication, SEND/SUBSCRIBE rules), and rejections become
 *       typed ERROR frames via {@link SockbowlStompErrorHandler}.</li>
 * </ul>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    public static final String STOMP_ENDPOINT = "/sockbowl-game";
    public static final String BROKER_PREFIX = "/queue";
    public static final String APP_PREFIX = "/app";
    public static final String USER_PREFIX = "/user";

    private final GameSessionInjectionResolver gameSessionInjectionResolver;
    private final StompInboundInterceptor stompInboundInterceptor;

    @Value("${sockbowl.websocket.allowed-origins}")
    private String[] allowedOrigins;

    public WebSocketConfig(GameSessionInjectionResolver gameSessionInjectionResolver,
                           StompInboundInterceptor stompInboundInterceptor) {
        this.gameSessionInjectionResolver = gameSessionInjectionResolver;
        this.stompInboundInterceptor = stompInboundInterceptor;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker(BROKER_PREFIX);
        config.setApplicationDestinationPrefixes(APP_PREFIX);
        config.setUserDestinationPrefix(USER_PREFIX);
        // Deliver messages to each client in the order they were published:
        // outbound frames otherwise go through a thread pool, so a client can
        // get a newer game-state update before an older one.
        config.setPreservePublishOrder(true);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(STOMP_ENDPOINT)
                .setAllowedOriginPatterns(allowedOrigins);
        registry.setErrorHandler(new SockbowlStompErrorHandler());
    }

    @Override
    public void configureClientInboundChannel(@NotNull ChannelRegistration registration) {
        registration.interceptors(stompInboundInterceptor);
        // Handle each client's frames in the order it sent them (see
        // SessionOrderedExecutor): two back-to-back SENDs, such as set-proctor
        // then set-match-packet, otherwise can reach Kafka swapped.
        registration.executor(new SessionOrderedExecutor(inboundPool, INBOUND_LANES));
    }

    /** Session lanes for the inbound channel. */
    private static final int INBOUND_LANES = 256;

    /**
     * The pool behind the inbound channel, sized like Spring's default one.
     * Deliberately not a bean: an Executor bean would make Spring Boot back
     * off its own application task executor.
     */
    private final ThreadPoolTaskExecutor inboundPool = newInboundPool();

    private static ThreadPoolTaskExecutor newInboundPool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Runtime.getRuntime().availableProcessors() * 2);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("clientInboundChannel-");
        executor.initialize();
        return executor;
    }

    @PreDestroy
    void shutdownInboundPool() {
        inboundPool.shutdown();
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> argumentResolvers) {
        argumentResolvers.add(gameSessionInjectionResolver);
    }
}
