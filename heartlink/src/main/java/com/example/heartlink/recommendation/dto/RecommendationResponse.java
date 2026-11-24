package com.example.heartlink.recommendation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record RecommendationResponse(List<Profile> items,
                                     @JsonProperty("next_cursor") Long nextCursor) {
    public RecommendationResponse(List<Profile> items) {
        this(items, null);
    }

    public record Profile(Long id, String name, String bio) {}
}
