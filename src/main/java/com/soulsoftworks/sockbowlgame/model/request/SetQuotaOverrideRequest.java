package com.soulsoftworks.sockbowlgame.model.request;

import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code PUT /api/v1/admin/usage/{sub}/quota/{metric}} (plan m4-limits section
 * 2.8): {@code {"limit": 4}} sets an override, {@code {"limit": null}} (or an
 * absent field) clears it back to the tier default. {@code -1} means
 * unlimited; anything more negative is rejected (G-M4-V1-09). {@code @Min}
 * only applies to a non-null value, so clearing is unaffected; it also isn't
 * a substitute for {@code AdminUsageService.setQuotaOverride}'s own check
 * (nothing in this codebase wires {@code @Valid} to an automatic 400 that both
 * this DTO's caller and its lower-level callers, e.g. a resync, can rely on).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SetQuotaOverrideRequest {

    @Min(-1)
    private Long limit;
}
