package com.soulsoftworks.sockbowlgame.ratelimit;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * Registers the <b>shipped</b> {@code sockbowl.ratelimit.*} keys (policies,
 * routes, exemptions, tier multipliers) from {@code src/main/resources/application.properties}
 * into a Spring test context.
 *
 * <p>The test classpath's own {@code application.properties} shadows the main
 * one, so without this a {@code @SpringBootTest} would run with no policies at
 * all. Values keep their {@code ${SOCKBOWL_RL_*:default}} placeholders, which
 * the environment resolves to the shipped defaults, so the request-guard ITs
 * prove the real configuration rather than a copy of it.
 */
public final class ShippedLimitProperties {

    private static final String MAIN_PROPERTIES = "src/main/resources/application.properties";

    private ShippedLimitProperties() {
    }

    public static Properties load() {
        try {
            return PropertiesLoaderUtils.loadProperties(new FileSystemResource(MAIN_PROPERTIES));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Registers every shipped {@code sockbowl.ratelimit.*} key and turns the limiter on. */
    public static void registerRateLimits(DynamicPropertyRegistry registry) {
        Properties shipped = load();
        for (String name : shipped.stringPropertyNames()) {
            if (name.startsWith("sockbowl.ratelimit.") || name.equals("sockbowl.auth.service-client-id")) {
                String value = shipped.getProperty(name);
                registry.add(name, () -> value);
            }
        }
        registry.add("sockbowl.ratelimit.enabled", () -> "true");
    }
}
