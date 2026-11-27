package com.example.heartlink.common.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketRateLimiterTests {
    @Test
    void refillsTokensContinuouslyAndReturnsTheWaitTime() {
        var clock = new MutableClock(Instant.parse("2026-11-27T00:00:00Z"));
        var limiter = new TokenBucketRateLimiter(clock);
        var policy = new RateLimitProperties.Policy(1, 1, Duration.ofSeconds(30));

        assertThat(limiter.tryAcquire(1L, "swipe", policy)).isEmpty();
        assertThat(limiter.tryAcquire(1L, "swipe", policy)).contains(Duration.ofSeconds(30));
        clock.advance(Duration.ofSeconds(15));
        assertThat(limiter.tryAcquire(1L, "swipe", policy)).contains(Duration.ofSeconds(15));
        clock.advance(Duration.ofSeconds(15));
        assertThat(limiter.tryAcquire(1L, "swipe", policy)).isEmpty();
    }

    @Test
    void separatesBucketsByUserAndOperation() {
        var clock = new MutableClock(Instant.parse("2026-11-27T00:00:00Z"));
        var limiter = new TokenBucketRateLimiter(clock);
        var policy = new RateLimitProperties.Policy(1, 1, Duration.ofMinutes(1));

        assertThat(limiter.tryAcquire(1L, "swipe", policy)).isEmpty();
        assertThat(limiter.tryAcquire(1L, "swipe", policy)).isPresent();
        assertThat(limiter.tryAcquire(2L, "swipe", policy)).isEmpty();
        assertThat(limiter.tryAcquire(1L, "chat-send", policy)).isEmpty();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}