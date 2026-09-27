package com.soulsoftworks.sockbowlgame.controller.api;

import com.soulsoftworks.sockbowlgame.model.request.ResetUsageRequest;
import com.soulsoftworks.sockbowlgame.model.request.SetQuotaOverrideRequest;
import com.soulsoftworks.sockbowlgame.model.response.GlobalUsage;
import com.soulsoftworks.sockbowlgame.model.response.UsageCounter;
import com.soulsoftworks.sockbowlgame.model.response.UsageDetail;
import com.soulsoftworks.sockbowlgame.model.response.UsageEvent;
import com.soulsoftworks.sockbowlgame.model.response.UserUsageSummary;
import com.soulsoftworks.sockbowlgame.usage.AdminUsageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Admin REST API for cross-user usage, quotas and rate-limit rejections (plan
 * m4-limits section 2.8, WP-G6, M4-AD-01).
 *
 * <p>Requires {@code admin:access}, already enforced both by the
 * {@code /api/v1/admin/**} URL rule in
 * {@link com.soulsoftworks.sockbowlgame.config.SecurityConfig} (and
 * {@code NoSecurityConfig}'s permissive stand-in) and, here, by method
 * security. Only active when authentication is enabled: with auth off there is
 * no durable user to page over and no admin token to relay to
 * sockbowl-questions.
 *
 * <ul>
 *   <li>{@code GET /api/v1/admin/usage?page&size&q&sort=lastSeen}: a page of
 *       {@link UserUsageSummary}.</li>
 *   <li>{@code GET /api/v1/admin/usage/{sub}}: {@link UsageDetail}, or 404.</li>
 *   <li>{@code GET /api/v1/admin/usage/global}: {@link GlobalUsage}.</li>
 *   <li>{@code GET /api/v1/admin/usage/events?limit=100}: recent
 *       {@code rl:events}, newest first.</li>
 *   <li>{@code PUT /api/v1/admin/usage/{sub}/quota/{metric}} with
 *       {@code {"limit": N|null}}: sets or clears an override.</li>
 *   <li>{@code POST /api/v1/admin/usage/{sub}/reset} with an optional
 *       {@code {"metric": "..."}}: clears today's counter(s).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/usage")
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "true")
@PreAuthorize("hasAuthority('admin:access')")
public class AdminUsageController {

    private final AdminUsageService adminUsageService;

    public AdminUsageController(AdminUsageService adminUsageService) {
        this.adminUsageService = adminUsageService;
    }

    @GetMapping
    public Page<UserUsageSummary> listUsers(@RequestParam(required = false) String q,
                                            Pageable pageable,
                                            @AuthenticationPrincipal Jwt jwt) {
        return adminUsageService.listUsers(pageable, q, tokenOf(jwt));
    }

    @GetMapping("/global")
    public GlobalUsage global() {
        return adminUsageService.getGlobal();
    }

    @GetMapping("/events")
    public List<UsageEvent> events(@RequestParam(defaultValue = "100") int limit) {
        return adminUsageService.recentEvents(limit);
    }

    @GetMapping("/{sub}")
    public UsageDetail detail(@PathVariable String sub, @AuthenticationPrincipal Jwt jwt) {
        return adminUsageService.getDetail(sub, tokenOf(jwt))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such user"));
    }

    @PutMapping("/{sub}/quota/{metric}")
    public UsageCounter setQuota(@PathVariable String sub,
                                @PathVariable String metric,
                                @RequestBody SetQuotaOverrideRequest request,
                                @AuthenticationPrincipal Jwt jwt) {
        try {
            return adminUsageService.setQuotaOverride(sub, metric, request.getLimit(),
                    jwt != null ? jwt.getSubject() : null);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/{sub}/reset")
    public ResponseEntity<Void> reset(@PathVariable String sub,
                                      @RequestBody(required = false) ResetUsageRequest request) {
        try {
            adminUsageService.resetUsage(sub, request == null ? null : request.getMetric());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    private static String tokenOf(Jwt jwt) {
        return jwt == null ? null : jwt.getTokenValue();
    }
}
