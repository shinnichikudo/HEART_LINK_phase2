package com.example.heartlink.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

@ConfigurationProperties(prefix = "heartlink.rate-limit")
public record RateLimitProperties(Policy swipe, Policy recommendations,
                                  Policy chatSend, Policy chatHistory) {
    public RateLimitProperties {
        Objects.requireNonNull(swipe, "swipe policy is required");
        Objects.requireNonNull(recommendations, "recommendations policy is required");
        Objects.requireNonNull(chatSend, "chat-send policy is required");
        Objects.requireNonNull(chatHistory, "chat-history policy is required");
    }

    public record Policy(int capacity, int refillTokens, Duration refillPeriod) {
        public Policy {
            if (capacity < 1 || refillTokens < 1 || refillPeriod == null || refillPeriod.isZero()
                    || refillPeriod.isNegative()) {
                throw new IllegalArgumentException("Rate limit capacity and refill period must be positive");
            }
        }
    }
}