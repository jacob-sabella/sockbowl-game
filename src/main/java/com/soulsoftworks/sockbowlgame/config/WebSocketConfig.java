package com.soulsoftworks.sockbowlgame.config;

import com.soulsoftworks.sockbowlgame.controller.resolver.GameSessionInjectionResolver;
import com.soulsoftworks.sockbowlgame.security.stomp.SockbowlStompErrorHandler;
import com.soulsoftworks.sockbowlgame.security.stomp.StompInboundInterceptor;
import com.soulsoftworks.sockbowlgame.websocket.ClientIpHandshakeInterceptor;
import jakarta.servlet.ServletContext;
import jakarta.websocket.server.ServerContainer;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.handler.invocation.HandlerMethodArgumentResolver;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.context.ServletContextAware;

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
 *   <li>M4 (WP-G3): the handshake stores the client address
 *       ({@link ClientIpHandshakeInterceptor}) for the STOMP limiter and IP-ban
 *       check, and the transport is bounded by {@link WebSocketLimitsProperties}
 *       ({@code sockbowl.websocket.*}: 16 KiB messages, 512 KiB send buffer, 15s
 *       send time, 30s to the first frame). The servlet container's own
 *       WebSocket text/binary buffers are set to the same message size, so the
 *       configured limit is the effective one (Tomcat's default is 8 KiB).</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableWebSocketMessageBroker
@EnableConfigurationProperties(WebSocketLimitsProperties.class)
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer, ServletContextAware {

    public static final String STOMP_ENDPOINT = "/sockbowl-game";
    public static final String BROKER_PREFIX = "/queue";
    public static final String APP_PREFIX = "/app";
    public static final String USER_PREFIX = "/user";

    private final GameSessionInjectionResolver gameSessionInjectionResolver;
    private final StompInboundInterceptor stompInboundInterceptor;
    private final ClientIpHandshakeInterceptor clientIpHandshakeInterceptor;
    private final WebSocketLimitsProperties limits;

    @Value("${sockbowl.websocket.allowed-origins}")
    private String[] allowedOrigins;

    @Autowired
    public WebSocketConfig(GameSessionInjectionResolver gameSessionInjectionResolver,
                           StompInboundInterceptor stompInboundInterceptor,
                           ClientIpHandshakeInterceptor clientIpHandshakeInterceptor,
                           WebSocketLimitsProperties limits) {
        this.gameSessionInjectionResolver = gameSessionInjectionResolver;
        this.stompInboundInterceptor = stompInboundInterceptor;
        this.clientIpHandshakeInterceptor = clientIpHandshakeInterceptor;
        this.limits = limits;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker(BROKER_PREFIX);
        config.setApplicationDestinationPrefixes(APP_PREFIX);
        config.setUserDestinationPrefix(USER_PREFIX);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(STOMP_ENDPOINT)
                .setAllowedOriginPatterns(allowedOrigins)
                .addInterceptors(clientIpHandshakeInterceptor);
        registry.setErrorHandler(new SockbowlStompErrorHandler());
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(bytes(limits.getMessageSizeLimit().toBytes()));
        registration.setSendBufferSizeLimit(bytes(limits.getSendBufferSizeLimit().toBytes()));
        registration.setSendTimeLimit(millis(limits.getSendTimeLimit().toMillis()));
        registration.setTimeToFirstMessage(millis(limits.getTimeToFirstMessage().toMillis()));
    }

    /**
     * Sizes the container's WebSocket buffers to the STOMP message limit. Only a
     * real (embedded) server has a {@link ServerContainer}; mock-servlet test
     * contexts have none and are left alone.
     */
    @Override
    public void setServletContext(ServletContext servletContext) {
        if (servletContext.getAttribute(ServerContainer.class.getName()) instanceof ServerContainer container) {
            int size = bytes(limits.getMessageSizeLimit().toBytes());
            container.setDefaultMaxTextMessageBufferSize(size);
            container.setDefaultMaxBinaryMessageBufferSize(size);
            log.debug("WebSocket container buffers set to {} bytes", size);
        }
    }

    private static int bytes(long value) {
        return (int) Math.min(Integer.MAX_VALUE, value);
    }

    private static int millis(long value) {
        return (int) Math.min(Integer.MAX_VALUE, value);
    }

    @Override
    public void configureClientInboundChannel(@NotNull ChannelRegistration registration) {
        registration.interceptors(stompInboundInterceptor);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> argumentResolvers) {
        argumentResolvers.add(gameSessionInjectionResolver);
    }
}
