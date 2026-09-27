package com.soulsoftworks.sockbowlgame.controller.api;

import com.soulsoftworks.sockbowlgame.model.entity.IpBan;
import com.soulsoftworks.sockbowlgame.model.request.CreateIpBanRequest;
import com.soulsoftworks.sockbowlgame.model.response.IpBanResponse;
import com.soulsoftworks.sockbowlgame.service.ban.IpBanService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Admin REST API for IP/CIDR bans (D8, AB-02; plan m4-limits section 2.4).
 *
 * <p>Requires {@code user:ban}, like {@link AdminBanController}: the
 * {@code /api/v1/admin/bans/**} URL rule in
 * {@link com.soulsoftworks.sockbowlgame.config.SecurityConfig} plus method
 * security. Only active when authentication is enabled.
 *
 * <ul>
 *   <li>{@code GET /api/v1/admin/bans/ip}: active bans, newest first.</li>
 *   <li>{@code POST /api/v1/admin/bans/ip} with
 *       {@code {"cidr","reason","ttlSeconds"|"expiresAt"}}: 201 with the ban;
 *       400 for an invalid or too broad range (wider than /16 or /48) or a
 *       missing, past or over-30-day expiry.</li>
 *   <li>{@code DELETE /api/v1/admin/bans/ip/{id}}: 204, or 404.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/bans/ip")
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "true")
@PreAuthorize("hasAuthority('user:ban')")
public class AdminIpBanController {

    private final IpBanService ipBanService;

    public AdminIpBanController(IpBanService ipBanService) {
        this.ipBanService = ipBanService;
    }

    @GetMapping
    public List<IpBanResponse> listIpBans() {
        return ipBanService.listActive().stream()
                .map(IpBanResponse::fromEntity)
                .toList();
    }

    @PostMapping
    public ResponseEntity<IpBanResponse> createIpBan(@Valid @RequestBody CreateIpBanRequest request,
                                                     @AuthenticationPrincipal Jwt jwt) {
        String bannedBy = jwt != null ? jwt.getSubject() : null;
        try {
            IpBan ban = ipBanService.create(
                    request.getCidr(),
                    request.getReason(),
                    bannedBy,
                    request.getTtlSeconds() == null ? null : Duration.ofSeconds(request.getTtlSeconds()),
                    IpBanService.parseInstant(request.getExpiresAt()));
            return ResponseEntity.status(HttpStatus.CREATED).body(IpBanResponse.fromEntity(ban));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @DeleteMapping("/{banId}")
    public ResponseEntity<Void> removeIpBan(@PathVariable UUID banId) {
        if (!ipBanService.remove(banId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "IP ban not found");
        }
        return ResponseEntity.noContent().build();
    }
}
