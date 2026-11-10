package com.example.heartlink.swipe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Đo lường và thống kê riêng thời gian chờ khóa (Lock Wait Time) của các thao tác swipe.
 * Cho phép phân tích định lượng thời gian chờ khóa trước khi quyết định cải tiến.
 */
@Component
public class SwipeLockMetrics {
    private static final Logger log = LoggerFactory.getLogger(SwipeLockMetrics.class);

    private final LongAdder totalSwipes = new LongAdder();
    private final LongAdder totalLockWaitNanos = new LongAdder();
    private final AtomicLong maxLockWaitNanos = new AtomicLong(0);
    private final AtomicLong lastLockWaitNanos = new AtomicLong(0);

    public void recordLockWait(Long userA, Long userB, long waitNanos) {
        totalSwipes.increment();
        totalLockWaitNanos.add(waitNanos);
        lastLockWaitNanos.set(waitNanos);
        maxLockWaitNanos.accumulateAndGet(waitNanos, Math::max);

        long waitMs = waitNanos / 1_000_000;
        if (waitMs > 50) {
            log.warn("High swipe lock wait time: pair=({}, {}), wait={}ms", userA, userB, waitMs);
        } else {
            log.debug("Swipe lock acquired: pair=({}, {}), wait={}ms", userA, userB, waitMs);
        }
    }

    public long getTotalSwipes() {
        return totalSwipes.sum();
    }

    public long getTotalLockWaitMillis() {
        return totalLockWaitNanos.sum() / 1_000_000;
    }

    public long getMaxLockWaitMillis() {
        return maxLockWaitNanos.get() / 1_000_000;
    }

    public long getLastLockWaitMillis() {
        return lastLockWaitNanos.get() / 1_000_000;
    }

    public double getAverageLockWaitMillis() {
        long count = totalSwipes.sum();
        return count == 0 ? 0.0 : (double) totalLockWaitNanos.sum() / (count * 1_000_000.0);
    }

    public void reset() {
        totalSwipes.reset();
        totalLockWaitNanos.reset();
        maxLockWaitNanos.set(0);
        lastLockWaitNanos.set(0);
    }
}
