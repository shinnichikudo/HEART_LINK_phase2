package com.example.heartlink.repository;

/** Minimal profile fields selected by the recommendation queries. */
public interface RecommendationProfileProjection {
    Long getId();

    String getName();

    String getBio();
}
