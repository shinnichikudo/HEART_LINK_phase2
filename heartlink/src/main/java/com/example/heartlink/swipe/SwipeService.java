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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class SwipeService {
    private static final Logger log = LoggerFactory.getLogger(SwipeService.class);

    private final UserRepository users;
    private final SwipeRepository swipes;
    private final MatchService matches;
    private final SwipeLockManager lockManager;
    private final SwipeLockMetrics lockMetrics;

    @Value("${heartlink.swipe.lock-strategy:pair}")
    private String lockStrategy = "pair";

    @Value("${heartlink.swipe.idempotent-replay:false}")
    private boolean idempotentReplay = false;

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

    public void setIdempotentReplay(boolean idempotentReplay) {
        this.idempotentReplay = idempotentReplay;
    }

    public boolean isIdempotentReplay() {
        return this.idempotentReplay;
    }

    public SwipeLockMetrics getLockMetrics() {
        return this.lockMetrics;
    }

    public SwipeLockManager getLockManager() {
        return this.lockManager;
    }

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

        // Đo riêng thời gian chờ khóa (Lock Wait Time) trước khi tiến hành xử lý nghiệp vụ
        long lockStart = System.nanoTime();
        User first;
        User second;

        if ("row".equalsIgnoreCase(lockStrategy)) {
            // Chiến lược cũ: Khóa theo dòng của từng user trong cặp theo thứ tự ID tăng dần
            first = lockUserRow(Math.min(userId, targetId), userId);
            second = lockUserRow(Math.max(userId, targetId), userId);
        } else {
            // Chiến lược mới: Khóa theo cặp (Pair-level locking).
            // A-B và A-C chạy song song (2 cặp độc lập). A-B và B-A dùng chung một khóa cặp.
            lockManager.acquirePairLock(userId, targetId);
            first = findUser(Math.min(userId, targetId), userId);
            second = findUser(Math.max(userId, targetId), userId);
        }

        long lockWaitNs = System.nanoTime() - lockStart;
        lockMetrics.recordLockWait(userId, targetId, lockWaitNs);

        User swiper = first.getId().equals(userId) ? first : second;
        User target = first.getId().equals(targetId) ? first : second;

        SwipeAction action = "like".equals(request.action()) ? SwipeAction.LIKE : SwipeAction.PASS;
        Optional<Swipe> existingOpt = swipes.findBySwiperIdAndTargetId(userId, targetId);

        if (existingOpt.isPresent()) {
            Swipe existing = existingOpt.get();
            boolean isIdempotentRetry = (idempotencyKey != null && !idempotencyKey.isBlank())
                    || idempotentReplay;

            // Xử lý mất phản hồi mạng (Idempotent retry):
            // Nếu có idempotencyKey (hoặc bật idempotentReplay) và cùng một hành động quẹt,
            // trả về đúng kết quả lượt thích trước đó thay vì báo lỗi 409, không tạo thêm swipe hay match trùng lặp.
            if (isIdempotentRetry && existing.getAction() == action) {
                log.info("Idempotent swipe retry: user={} target={} action={}", userId, targetId, action);
                SwipeResponse.MatchInfo matchInfo = null;
                if (action == SwipeAction.LIKE) {
                    Optional<Match> matchOpt = matches.findMatchBetween(userId, targetId);
                    if (matchOpt.isPresent()) {
                        matchInfo = new SwipeResponse.MatchInfo(matchOpt.get().getId(), targetId);
                    }
                }
                return new SwipeResponse(existing.getId(), matchInfo != null, matchInfo);
            }

            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_SWIPED", "You have already swiped this user");
        }

        Swipe swipe = swipes.saveAndFlush(Swipe.builder().swiper(swiper).target(target).action(action).build());

        SwipeResponse.MatchInfo matchInfo = null;
        if (action == SwipeAction.LIKE
                && swipes.existsBySwiperIdAndTargetIdAndAction(targetId, userId, SwipeAction.LIKE)) {
            Match match = matches.createForMutualLike(first, second);
            matchInfo = new SwipeResponse.MatchInfo(match.getId(), targetId);
        }
        return new SwipeResponse(swipe.getId(), matchInfo != null, matchInfo);
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
