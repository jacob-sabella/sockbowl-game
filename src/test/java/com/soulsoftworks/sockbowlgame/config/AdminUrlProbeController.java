package com.soulsoftworks.sockbowlgame.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Throwaway controller used only by {@link AdminUrlAuthorizationTest} to
 * exercise {@link SecurityConfig}'s URL-level authorization rules for the
 * {@code /api/v1/admin/**} space via a {@code @WebMvcTest} slice.
 *
 * <p>This must be a top-level class (not nested inside the test class):
 * Spring Boot's {@code TestTypeExcludeFilter} excludes classes whose
 * enclosing class is a JUnit test class from component scanning, so a
 * nested {@code @RestController} inside the test class would silently fail
 * to register as a bean, and every request would 404 instead of exercising
 * the security filter chain.
 *
 * <p>Gated on {@code sockbowl.test.url-probes=true}, which only the URL slice
 * tests set: it lives in a scanned package, so without the gate every full
 * {@code @SpringBootTest} context would register it too, and with auth on its
 * mappings would collide with the real controllers.
 */
@RestController
@ConditionalOnProperty(name = "sockbowl.test.url-probes", havingValue = "true")
public class AdminUrlProbeController {

    @GetMapping("/api/v1/admin/bans")
    public String bans() {
        return "ok";
    }

    @GetMapping("/api/v1/admin/console")
    public String console() {
        return "ok";
    }
}
