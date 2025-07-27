package com.soulsoftworks.sockbowlgame.config;

import com.soulsoftworks.sockbowlgame.controller.resolver.GameSessionInjectionResolver;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.handler.invocation.HandlerMethodArgumentResolver;
import org.springframework.messaging.handler.invocation.HandlerMethodReturnValueHandler;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

import java.util.List;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {


    private final GameSessionInjectionResolver gameSessionInjectionResolver;


    public static String STOMP_ENDPOINT = "/sockbowl-game";

    public WebSocketConfig(GameSessionInjectionResolver gameSessionInjectionResolver) {
        this.gameSessionInjectionResolver = gameSessionInjectionResolver;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/queue/", "/user");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(STOMP_ENDPOINT)
                .setAllowedOriginPatterns(
                        "http://localhost:4200",
                        "http://localhost:80",
                        "http://localhost",
                        "http://*.sockbowl.*:*",
                        "https://alpha.sockbowl.com",
                        "https://ng.alpha.sockbowl.com",
                        "https://alpha.sockbowl.com/*",
                        "https://ng.alpha.sockbowl.com/*",
                        "https://alpha.sockbowl.com:7003",
                        "http://ng.sockbowl.localhost"
                );
    }

    @Override
    public void configureWebSocketTransport(@NotNull WebSocketTransportRegistration registry) {
        WebSocketMessageBrokerConfigurer.super.configureWebSocketTransport(registry);
    }

    @Override
    public void configureClientInboundChannel(@NotNull ChannelRegistration registration) {
        WebSocketMessageBrokerConfigurer.super.configureClientInboundChannel(registration);
    }

    @Override
    public void configureClientOutboundChannel(@NotNull ChannelRegistration registration) {
        WebSocketMessageBrokerConfigurer.super.configureClientOutboundChannel(registration);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> argumentResolvers) {
        argumentResolvers.add(gameSessionInjectionResolver);
        WebSocketMessageBrokerConfigurer.super.addArgumentResolvers(argumentResolvers);
    }

    @Override
    public void addReturnValueHandlers(@NotNull List<HandlerMethodReturnValueHandler> returnValueHandlers) {
        WebSocketMessageBrokerConfigurer.super.addReturnValueHandlers(returnValueHandlers);
    }

    @Override
    public boolean configureMessageConverters(@NotNull List<MessageConverter> messageConverters) {
        return WebSocketMessageBrokerConfigurer.super.configureMessageConverters(messageConverters);
    }

}
