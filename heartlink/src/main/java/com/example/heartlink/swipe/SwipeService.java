package com.example.heartlink.swipe;

import com.example.heartlink.common.error.ApiException;
import com.example.heartlink.entity.Match;
import com.example.heartlink.entity.Swipe;
import com.example.heartlink.entity.SwipeAction;
import com.example.heartlink.entity.User;
import com.example.heartlink.match.MatchService;
import com.example.heartlink.repository.SwipeRepository;
import com.example.heartlink.repository.UserRepository;
import com.example.heartlink.swipe.dto.SwipeRequest;
import com.example.heartlink.swipe.dto.SwipeResponse;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class SwipeService {
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[!-~]{1,128}");

    private final UserRepository users;
    private final SwipeRepository swipes;
    private final MatchService matches;
    private final SwipeLockManager lockManager;
    private final SwipeLockMetrics lockMetrics;

    @Value("${heartlink.swipe.lock-strategy:pair}")
    private String lockStrategy = "pair";

    @Autowired
    public SwipeService(UserRepository users, SwipeRepository swipes, MatchService matches,
                        SwipeLockManager lockManager, SwipeLockMetrics lockMetrics) {
        this.users = users;
        this.swipes = swipes;
        this.matches = matches;
        this.lockManager = lockManager;
        this.lockMetrics = lockMetrics;
    }

    public void setLockStrategy(String lockStrategy) {
        this.lockStrategy = lockStrategy;
    }

    public String getLockStrategy() {
        return this.lockStrategy;
    }

    public SwipeLockMetrics getLockMetrics() {
        return this.lockMetrics;
    }

    public SwipeLockManager getLockManager() {
        return this.lockManager;
    }

    // Both public entry points need a transaction: internal delegation bypasses the Spring proxy.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SwipeResponse swipe(Long userId, SwipeRequest request) {
        return swipe(userId, request, request.idempotencyKey());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SwipeResponse swipe(Long userId, SwipeRequest request, String idempotencyKey) {
        Long targetId = request.targetId();
        if (targetId == null || targetId <= 0
                || !("like".equals(request.action()) || "pass".equals(request.action()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Invalid target_id or action");
        }
        if (userId.equals(targetId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SELF_SWIPE", "Cannot swipe yourself");
        }

        String key = resolveIdempotencyKey(request.idempotencyKey(), idempotencyKey);
        SwipeAction action = "like".equals(request.action()) ? SwipeAction.LIKE : SwipeAction.PASS;
        if (key != null) {
            // Only committed, complete snapshots are visible. Replays need no pair lock or live match lookup.
            Optional<Swipe> previous = swipes.findBySwiperIdAndIdempotencyKey(userId, key);
            if (previous.isPresent()) {
                return replay(previous.get(), targetId, action);
            }
        }

        User first;
        User second;

        if ("row".equalsIgnoreCase(lockStrategy)) {
            // Chiến lược cũ: Khóa theo dòng của từng user trong cặp theo thứ tự ID tăng dần
            long lockStart = System.nanoTime();
            first = lockUserRow(Math.min(userId, targetId), userId);
            second = lockUserRow(Math.max(userId, targetId), userId);
            lockMetrics.recordLockWait(userId, targetId, System.nanoTime() - lockStart);
        } else {
            // Chiến lược mới: Khóa theo cặp (Pair-level locking).
            // A-B và A-C chạy song song (2 cặp độc lập). A-B và B-A dùng chung một khóa cặp.
            long lockStart = System.nanoTime();
            lockManager.acquirePairLock(userId, targetId);
            // Exclude the separate user reads from pair-lock acquisition time.
            lockMetrics.recordLockWait(userId, targetId, System.nanoTime() - lockStart);
            first = findUser(Math.min(userId, targetId), userId);
            second = findUser(Math.max(userId, targetId), userId);
        }

        User swiper = first.getId().equals(userId) ? first : second;
        User target = first.getId().equals(targetId) ? first : second;

        Optional<Swipe> existingOpt = swipes.findBySwiperIdAndTargetId(userId, targetId);

        if (existingOpt.isPresent()) {
            Swipe existing = existingOpt.get();
            // Another request may have committed while this request waited for the pair lock.
            if (key != null && key.equals(existing.getIdempotencyKey())) {
                return replay(existing, targetId, action);
            }

            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_SWIPED", "You have already swiped this user");
        }

        Swipe swipe;
        try {
            swipe = swipes.saveAndFlush(Swipe.builder().swiper(swiper).target(target)
                    .action(action).idempotencyKey(key).build());
        } catch (DataIntegrityViolationException exception) {
            // Different pairs have different locks. The unique constraint resolves concurrent key reuse
            // across those pairs; propagate an API conflict and roll back without querying the aborted transaction.
            if (key != null && isIdempotencyKeyConflict(exception)) {
                throw idempotencyKeyReused();
            }
            throw exception;
        }

        SwipeResponse.MatchInfo matchInfo = null;
        if (action == SwipeAction.LIKE
                && swipes.existsBySwiperIdAndTargetIdAndAction(targetId, userId, SwipeAction.LIKE)) {
            Match match = matches.createForMutualLike(first, second);
            matchInfo = new SwipeResponse.MatchInfo(match.getId(), targetId);
            if (key != null) {
                // The managed entity is flushed with the match and notifications at transaction commit.
                swipe.setResponseMatchId(match.getId());
            }
        }
        return new SwipeResponse(swipe.getId(), matchInfo != null, matchInfo);
    }

    private static String resolveIdempotencyKey(String bodyKey, String headerKey) {
        if (bodyKey != null && headerKey != null && !bodyKey.equals(headerKey)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Idempotency-Key header and idempotency_key body must match");
        }
        String key = headerKey != null ? headerKey : bodyKey;
        if (key != null && !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Idempotency key must contain 1 to 128 visible ASCII characters without spaces");
        }
        return key;
    }

    private static SwipeResponse replay(Swipe swipe, Long targetId, SwipeAction action) {
        if (!swipe.getTarget().getId().equals(targetId) || swipe.getAction() != action) {
            throw idempotencyKeyReused();
        }
        Long matchId = swipe.getResponseMatchId();
        var match = matchId == null ? null : new SwipeResponse.MatchInfo(matchId, targetId);
        return new SwipeResponse(swipe.getId(), match != null, match);
    }

    private static boolean isIdempotencyKeyConflict(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "uq_swipe_idempotency_key".equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    private static ApiException idempotencyKeyReused() {
        return new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                "This idempotency key has already been used for a different swipe request");
    }

    private User lockUserRow(Long id, Long currentUserId) {
        return users.findByIdForUpdate(id).orElseThrow(() -> id.equals(currentUserId)
                ? new ApiException(HttpStatus.UNAUTHORIZED, "USER_NOT_FOUND", "User does not exist")
                : new ApiException(HttpStatus.NOT_FOUND, "TARGET_NOT_FOUND", "Target user does not exist"));
    }

    private User findUser(Long id, Long currentUserId) {
        return users.findById(id).orElseThrow(() -> id.equals(currentUserId)
                ? new ApiException(HttpStatus.UNAUTHORIZED, "USER_NOT_FOUND", "User does not exist")
                : new ApiException(HttpStatus.NOT_FOUND, "TARGET_NOT_FOUND", "Target user does not exist"));
    }
}
