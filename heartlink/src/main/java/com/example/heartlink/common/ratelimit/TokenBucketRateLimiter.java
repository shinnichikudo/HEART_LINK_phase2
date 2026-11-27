package com.example.heartlink.common.ratelimit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** In-memory per-user buckets. Use shared storage if the application runs on multiple instances. */
@Component
public class TokenBucketRateLimiter {
    private static final Duration IDLE_BUCKET_TTL = Duration.ofHours(1);

    private final ConcurrentHashMap<Key, Bucket> buckets = new ConcurrentHashMap<>();
    private final Clock clock;

    public TokenBucketRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Returns the wait duration when the user has no token; empty means the request was allowed. */
    public Optional<Duration> tryAcquire(Long userId, String operation, RateLimitProperties.Policy policy) {
        var key = new Key(userId, operation);
        var now = clock.instant();
        var retryAfter = new AtomicReference<Duration>();
        buckets.compute(key, (ignored, current) -> {
            var bucket = current == null ? new Bucket(policy.capacity(), now) : current;
            retryAfter.set(bucket.acquire(policy, now));
            return bucket;
        });
        return Optional.ofNullable(retryAfter.get());
    }

    @Scheduled(fixedDelay = 300_000)
    public void removeIdleBuckets() {
        Instant cutoff = clock.instant().minus(IDLE_BUCKET_TTL);
        for (Key key : buckets.keySet()) {
            buckets.computeIfPresent(key, (ignored, bucket) -> bucket.wasLastTouchedBefore(cutoff) ? null : bucket);
        }
    }

    private record Key(Long userId, String operation) { }

    private static final class Bucket {
        private double tokens;
        private Instant lastRefill;
        private Instant lastTouched;

        private Bucket(int capacity, Instant now) {
            this.tokens = capacity;
            this.lastRefill = now;
            this.lastTouched = now;
        }

        private synchronized Duration acquire(RateLimitProperties.Policy policy, Instant now) {
            long elapsedNanos = Duration.between(lastRefill, now).toNanos();
            long periodNanos = policy.refillPeriod().toNanos();
            if (elapsedNanos > 0) {
                tokens = Math.min(policy.capacity(), tokens
                        + elapsedNanos * (double) policy.refillTokens() / periodNanos);
                lastRefill = now;
            }
            lastTouched = now;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return null;
            }
            long waitNanos = (long) Math.ceil((1.0 - tokens) * periodNanos / policy.refillTokens());
            return Duration.ofNanos(Math.max(1, waitNanos));
        }

        private synchronized boolean wasLastTouchedBefore(Instant cutoff) {
            return lastTouched.isBefore(cutoff);
        }
    }
}