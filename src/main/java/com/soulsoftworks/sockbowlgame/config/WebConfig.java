package com.soulsoftworks.sockbowlgame.config;

import com.soulsoftworks.sockbowlgame.ratelimit.LimitErrorResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableWebMvc
public class WebConfig implements WebMvcConfigurer {

    /**
     * Response headers a browser may read: the original two plus the M4 limit
     * headers ({@code Retry-After}, {@code X-RateLimit-*}) so ng can show a
     * cooldown on a 429 (plan m4-limits section 2.1).
     */
    static final String[] EXPOSED_HEADERS = exposedHeaders();

    @Value("${sockbowl.websocket.allowed-origins}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH")
                .allowedHeaders("*")
                .allowCredentials(true)
                .exposedHeaders(EXPOSED_HEADERS)
                .maxAge(3600);
    }

    private static String[] exposedHeaders() {
        List<String> headers = new ArrayList<>(List.of("Authorization", "Content-Type"));
        headers.addAll(LimitErrorResponses.EXPOSED_HEADERS);
        return headers.toArray(String[]::new);
    }
}
