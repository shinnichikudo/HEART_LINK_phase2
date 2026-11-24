package com.example.heartlink.swipe.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record SwipeRequest(
        @JsonProperty("target_id") @NotNull @Positive Long targetId,
        @NotNull @Pattern(regexp = "like|pass") String action,
        @JsonProperty("idempotency_key") @Size(min = 1, max = 128)
        @Pattern(regexp = "[!-~]+") String idempotencyKey) {

    public SwipeRequest(Long targetId, String action) {
        this(targetId, action, null);
    }
}
