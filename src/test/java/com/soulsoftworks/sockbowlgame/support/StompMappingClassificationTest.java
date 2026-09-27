package com.soulsoftworks.sockbowlgame.support;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@link StompMappingClassification} lists exactly the {@code @MessageMapping}
 * destinations the websocket controllers expose: nothing unclassified, nothing
 * stale. (WP-G4 additionally asserts this from the STOMP mapping inventory test.)
 */
class StompMappingClassificationTest {

    private static final String CONTROLLER_PACKAGE = "com.soulsoftworks.sockbowlgame.controller.websocket";

    @Test
    void classificationCoversExactlyTheDeclaredMappings() throws Exception {
        assertEquals(new TreeSet<>(declaredDestinations()), new TreeSet<>(StompMappingClassification.ALL));
    }

    static Set<String> declaredDestinations() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        Set<String> destinations = new TreeSet<>();
        for (var candidate : scanner.findCandidateComponents(CONTROLLER_PACKAGE)) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            MessageMapping typeMapping = type.getAnnotation(MessageMapping.class);
            String[] prefixes = typeMapping == null || typeMapping.value().length == 0
                    ? new String[]{""} : typeMapping.value();
            for (Method method : type.getDeclaredMethods()) {
                MessageMapping mapping = method.getAnnotation(MessageMapping.class);
                if (mapping == null) {
                    continue;
                }
                for (String prefix : prefixes) {
                    for (String path : mapping.value()) {
                        destinations.add(StompMappingClassification.APP_PREFIX + join(prefix, path));
                    }
                }
            }
        }
        assertFalse(destinations.isEmpty(), "no @MessageMapping found in " + CONTROLLER_PACKAGE);
        return destinations;
    }

    private static String join(String prefix, String path) {
        String p = prefix.isEmpty() ? "" : "/" + strip(prefix);
        return p + "/" + strip(path);
    }

    private static String strip(String s) {
        String r = s;
        while (r.startsWith("/")) {
            r = r.substring(1);
        }
        while (r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }
}
