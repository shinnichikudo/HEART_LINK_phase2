package com.example.heartlink.swipe;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TransactionRequiredException;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Quản lý khóa logic theo cặp (Pair-level Locking) cho luồng quẹt thẻ.
 * Giúp A-B và A-C xử lý song song, trong khi A-B và B-A dùng chung một khóa.
 */
@Component
public class SwipeLockManager {
    private final EntityManager entityManager;

    public SwipeLockManager(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager");
    }

    /**
     * Sinh khóa 64-bit đại diện cho cặp {id1, id2} không phụ thuộc thứ tự (đối xứng giữa A->B và B->A).
     * Với ID nằm trong khoảng 32-bit (<= 2^32 - 1), (min << 32) | (max & 0xFFFFFFFFL)
     * đảm bảo 100% không trùng lặp (collision-free) giữa các cặp khác nhau.
     */
    public static long pairKey(long id1, long id2) {
        long min = Math.min(id1, id2);
        long max = Math.max(id1, id2);
        if ((min >>> 32) == 0 && (max >>> 32) == 0) {
            return (min << 32) | (max & 0xFFFFFFFFL);
        }
        // Trường hợp ID 64-bit đầy đủ: kết hợp hash 64-bit Stafford variant
        long z = min * 0x9e3779b97f4a7c15L + max;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    /**
     * Lấy khóa transaction-level advisory lock trên PostgreSQL cho cặp (userId, targetId).
     * Khóa này tự động được giải phóng khi transaction commit hoặc rollback.
     * A failed acquisition must propagate so the swipe transaction cannot continue without its lock.
     */
    public void acquirePairLock(long userId, long targetId) {
        if (!entityManager.isJoinedToTransaction()) {
            throw new TransactionRequiredException("Pair locks require an active database transaction");
        }
        long key = pairKey(userId, targetId);
        entityManager.createNativeQuery("SELECT 1 FROM (SELECT pg_advisory_xact_lock(:key)) t")
                .setParameter("key", key)
                .getSingleResult();
    }
}
