package com.soulsoftworks.sockbowlgame.model.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code PUT /api/v1/admin/usage/{sub}/quota/{metric}} (plan m4-limits section
 * 2.8): {@code {"limit": 4}} sets an override, {@code {"limit": null}} (or an
 * absent field) clears it back to the tier default.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SetQuotaOverrideRequest {

    private Long limit;
}
