package com.soulsoftworks.sockbowlgame.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Probe endpoints for {@link SecurityConfigHttpMatrixTest}, for URL spaces that
 * have no real controller in the WebMvc slice: an arbitrary {@code /admin/**}
 * page (only {@code /admin/bans} exists today) and the actuator health path
 * (actuator isn't part of a WebMvc slice). Top-level so Spring registers it (a
 * class nested in a test class is excluded from scanning).
 *
 * <p>Gated on {@code sockbowl.test.url-probes=true}, which only the URL slice
 * tests set: it lives in a scanned package, so without the gate every full
 * {@code @SpringBootTest} context would register it too, and with auth on its
 * mappings would collide with the real controllers.
 */
@RestController
@ConditionalOnProperty(name = "sockbowl.test.url-probes", havingValue = "true")
public class SecurityMatrixProbeController {

    @GetMapping("/api/v1/admin/console")
    public String adminConsole() {
        return "ok";
    }

    @GetMapping("/actuator/health")
    public String health() {
        return "{\"status\":\"UP\"}";
    }
}
