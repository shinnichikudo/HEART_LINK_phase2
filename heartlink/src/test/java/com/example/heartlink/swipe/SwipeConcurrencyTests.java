package com.example.heartlink.swipe;

import com.example.heartlink.entity.Match;
import com.example.heartlink.entity.Swipe;
import com.example.heartlink.entity.SwipeAction;
import com.example.heartlink.entity.User;
import com.example.heartlink.match.MatchService;
import com.example.heartlink.repository.SwipeRepository;
import com.example.heartlink.repository.UserRepository;
import com.example.heartlink.swipe.dto.SwipeRequest;
import com.example.heartlink.swipe.dto.SwipeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Kiểm tra tính đúng đắn khi like đồng thời và đo riêng thời gian chờ khóa:
 * 1. A-B và B-A like đồng thời: dùng chung một khóa cặp, tạo đúng 1 match duy nhất.
 * 2. Hot profile A: B-A và C-A chạy song song vì là 2 cặp độc lập.
 */
class SwipeConcurrencyTests {
    private final UserRepository users = mock(UserRepository.class);
    private final SwipeRepository swipes = mock(SwipeRepository.class);
    private final MatchService matches = mock(MatchService.class);
    private final SwipeLockMetrics lockMetrics = new SwipeLockMetrics();

    // Giả lập khóa logic theo cặp trong bộ nhớ cho test đa luồng
    private final ConcurrentHashMap<Long, Semaphore> inMemoryLocks = new ConcurrentHashMap<>();
    private final SwipeLockManager mockLockManager = new SwipeLockManager() {
        @Override
        public void acquirePairLock(long userId, long targetId) {
            long key = pairKey(userId, targetId);
            Semaphore sem = inMemoryLocks.computeIfAbsent(key, k -> new Semaphore(1, true));
            try {
                sem.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
    };

    private SwipeService service;
    private final ConcurrentHashMap<String, SwipeAction> recordedSwipes = new ConcurrentHashMap<>();
    private final AtomicInteger matchCounter = new AtomicInteger(0);

    @BeforeEach
    void setUp() {
        inMemoryLocks.clear();
        recordedSwipes.clear();
        matchCounter.set(0);
        lockMetrics.reset();

        service = new SwipeService(users, swipes, matches, mockLockManager, lockMetrics) {
            @Override
            public SwipeResponse swipe(Long userId, SwipeRequest request) {
                // Wrapper để release in-memory lock sau khi xử lý xong (tương tự như transaction commit trong DB)
                long key = SwipeLockManager.pairKey(userId, request.targetId());
                try {
                    return super.swipe(userId, request);
                } finally {
                    Semaphore sem = inMemoryLocks.get(key);
                    if (sem != null) {
                        sem.release();
                    }
                }
            }
        };

        // Mock users 1, 2, 3
        for (long i = 1; i <= 3; i++) {
            User u = User.builder().id(i).build();
            when(users.findById(i)).thenReturn(Optional.of(u));
            when(users.findByIdForUpdate(i)).thenReturn(Optional.of(u));
        }

        // Giả lập lưu swipe thread-safe
        when(swipes.saveAndFlush(any())).thenAnswer(invocation -> {
            Swipe s = invocation.getArgument(0);
            s.setId(ThreadLocalRandom.current().nextLong(100, 1000));
            recordedSwipes.put(s.getSwiper().getId() + "->" + s.getTarget().getId(), s.getAction());
            return s;
        });

        when(swipes.existsBySwiperIdAndTargetId(anyLong(), anyLong())).thenAnswer(invocation -> {
            long swiperId = invocation.getArgument(0);
            long targetId = invocation.getArgument(1);
            return recordedSwipes.containsKey(swiperId + "->" + targetId);
        });

        when(swipes.existsBySwiperIdAndTargetIdAndAction(anyLong(), anyLong(), any())).thenAnswer(invocation -> {
            long swiperId = invocation.getArgument(0);
            long targetId = invocation.getArgument(1);
            SwipeAction act = invocation.getArgument(2);
            return recordedSwipes.get(swiperId + "->" + targetId) == act;
        });

        when(matches.createForMutualLike(any(), any())).thenAnswer(invocation -> {
            User first = invocation.getArgument(0);
            User second = invocation.getArgument(1);
            matchCounter.incrementAndGet();
            return Match.builder().id(999L).userA(first).userB(second).build();
        });
    }

    @Test
    void simultaneousLikesBetweenAAndBCreateExactlyOneMatchAndSerializeOnPairLock() throws Exception {
        Long userA = 1L;
        Long userB = 2L;

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Callable<SwipeResponse> swipeAtoB = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return service.swipe(userA, new SwipeRequest(userB, "like"));
        };

        Callable<SwipeResponse> swipeBtoA = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return service.swipe(userB, new SwipeRequest(userA, "like"));
        };

        Future<SwipeResponse> future1 = pool.submit(swipeAtoB);
        Future<SwipeResponse> future2 = pool.submit(swipeBtoA);

        SwipeResponse res1 = future1.get(10, TimeUnit.SECONDS);
        SwipeResponse res2 = future2.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        // Đúng 1 trong 2 lượt swipe nhận kết quả matched = true
        assertThat(res1.matched() ^ res2.matched()).isTrue();

        // Cả 2 lượt like đều được ghi nhận
        assertThat(recordedSwipes).hasSize(2);
        assertThat(recordedSwipes.get("1->2")).isEqualTo(SwipeAction.LIKE);
        assertThat(recordedSwipes.get("2->1")).isEqualTo(SwipeAction.LIKE);

        // Đúng 1 match duy nhất được tạo ra
        assertThat(matchCounter.get()).isEqualTo(1);

        // Thống kê lock wait time ghi nhận đủ 2 lần swipe
        assertThat(lockMetrics.getTotalSwipes()).isEqualTo(2);
        assertThat(lockMetrics.getLastLockWaitMillis()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void hotProfileAAllowsParallelSwipesFromBAndCWithoutLockContention() throws Exception {
        Long hotUserA = 1L;
        Long userB = 2L;
        Long userC = 3L;

        // B quẹt A và C quẹt A đồng thời
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Callable<SwipeResponse> swipeBtoA = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return service.swipe(userB, new SwipeRequest(hotUserA, "like"));
        };

        Callable<SwipeResponse> swipeCtoA = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return service.swipe(userC, new SwipeRequest(hotUserA, "like"));
        };

        Future<SwipeResponse> futureB = pool.submit(swipeBtoA);
        Future<SwipeResponse> futureC = pool.submit(swipeCtoA);

        SwipeResponse resB = futureB.get(10, TimeUnit.SECONDS);
        SwipeResponse resC = futureC.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(resB.matched()).isFalse();
        assertThat(resC.matched()).isFalse();

        // Cả 2 lượt quẹt của B->A và C->A đều thành công
        assertThat(recordedSwipes).containsEntry("2->1", SwipeAction.LIKE);
        assertThat(recordedSwipes).containsEntry("3->1", SwipeAction.LIKE);

        // Key đại diện 2 cặp là khác nhau -> không tranh chấp
        assertThat(SwipeLockManager.pairKey(hotUserA, userB))
                .isNotEqualTo(SwipeLockManager.pairKey(hotUserA, userC));
        assertThat(lockMetrics.getTotalSwipes()).isEqualTo(2);
    }
}
