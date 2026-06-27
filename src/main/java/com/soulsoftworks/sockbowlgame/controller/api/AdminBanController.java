package com.soulsoftworks.sockbowlgame.controller.api;

import com.soulsoftworks.sockbowlgame.model.entity.BanRecord;
import com.soulsoftworks.sockbowlgame.model.request.CreateBanRequest;
import com.soulsoftworks.sockbowlgame.model.response.BanResponse;
import com.soulsoftworks.sockbowlgame.service.BanService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Admin REST API for managing user bans.
 *
 * <p>Every method requires the {@code admin} realm role, enforced both by the URL
 * rule in {@link com.soulsoftworks.sockbowlgame.config.SecurityConfig} and by
 * method security ({@link PreAuthorize}). Only active when authentication is
 * enabled.
 */
@RestController
@RequestMapping("/api/v1/admin/bans")
@ConditionalOnProperty(name = "sockbowl.auth.enabled", havingValue = "true")
@PreAuthorize("hasRole('admin')")
public class AdminBanController {

    private final BanService banService;

    public AdminBanController(BanService banService) {
        this.banService = banService;
    }

    /**
     * List all currently-active bans.
     */
    @GetMapping
    public List<BanResponse> listBans() {
        return banService.listActiveBans().stream()
                .map(BanResponse::fromEntity)
                .toList();
    }

    /**
     * Create a new ban. The issuing admin is recorded from the JWT subject.
     */
    @PostMapping
    public ResponseEntity<BanResponse> createBan(@Valid @RequestBody CreateBanRequest request,
                                                 @AuthenticationPrincipal Jwt jwt) {
        String bannedBy = jwt != null ? jwt.getSubject() : null;
        BanRecord ban = banService.createBan(
                request.getBannedKeycloakId(),
                request.getReason(),
                bannedBy,
                request.getExpiresAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(BanResponse.fromEntity(ban));
    }

    /**
     * Remove a ban by id.
     */
    @DeleteMapping("/{banId}")
    public ResponseEntity<Void> removeBan(@PathVariable UUID banId) {
        if (!banService.removeBan(banId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ban not found");
        }
        return ResponseEntity.noContent().build();
    }
}
