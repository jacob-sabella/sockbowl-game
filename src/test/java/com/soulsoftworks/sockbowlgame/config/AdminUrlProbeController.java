package com.soulsoftworks.sockbowlgame.config;

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
 */
@RestController
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
