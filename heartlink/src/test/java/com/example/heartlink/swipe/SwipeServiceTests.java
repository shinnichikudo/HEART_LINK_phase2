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
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class SwipeServiceTests {
    final UserRepository users = mock(UserRepository.class);
    final SwipeRepository swipes = mock(SwipeRepository.class);
    final MatchService matches = mock(MatchService.class);
    final SwipeLockManager lockManager = mock(SwipeLockManager.class);
    final SwipeLockMetrics lockMetrics = new SwipeLockMetrics();
    final SwipeService service = new SwipeService(users, swipes, matches, lockManager, lockMetrics);

    void givenUsers() {
        when(users.findById(1L)).thenReturn(Optional.of(User.builder().id(1L).build()));
        when(users.findById(2L)).thenReturn(Optional.of(User.builder().id(2L).build()));
        when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(User.builder().id(1L).build()));
        when(users.findByIdForUpdate(2L)).thenReturn(Optional.of(User.builder().id(2L).build()));
        when(swipes.saveAndFlush(any())).thenAnswer(i -> {
            Swipe s = i.getArgument(0); s.setId(10L); return s;
        });
    }

    @Test
    void selfSwipeAndInvalidActionDoNotWrite() {
        assertThatThrownBy(() -> service.swipe(1L, new SwipeRequest(1L, "like")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("SELF_SWIPE"));
        assertThatThrownBy(() -> service.swipe(1L, new SwipeRequest(2L, "undo")))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(users, swipes, matches, lockManager);
    }

    @Test
    void missingTargetAndDeletedCallerHaveDifferentErrors() {
        when(users.findById(1L)).thenReturn(Optional.empty());
        when(users.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.swipe(2L, new SwipeRequest(1L, "like")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(404));
        assertThatThrownBy(() -> service.swipe(1L, new SwipeRequest(2L, "like")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(401));
        verifyNoInteractions(swipes, matches);
    }

    @Test
    void duplicateSwipeWithoutIdempotencyKeyThrowsConflict() {
        givenUsers();
        when(swipes.findBySwiperIdAndTargetId(1L, 2L)).thenReturn(Optional.of(
                Swipe.builder().id(5L).action(SwipeAction.LIKE).build()));

        // Không có idempotency_key và idempotentReplay=false -> ném 409
        assertThatThrownBy(() -> service.swipe(1L, new SwipeRequest(2L, "like")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(409));
        verify(swipes, never()).saveAndFlush(any());
        verifyNoInteractions(matches);
    }

    @Test
    void idempotentRetryWithKeyReturnsPreviousLikeAndMatchResult() {
        givenUsers();
        when(swipes.findBySwiperIdAndTargetId(1L, 2L)).thenReturn(Optional.of(
                Swipe.builder().id(55L).action(SwipeAction.LIKE).build()));
        when(matches.findMatchBetween(1L, 2L)).thenReturn(Optional.of(
                Match.builder().id(88L).build()));

        // Gửi lại cùng action 'like' kèm idempotency_key (giả lập retry khi mất mạng)
        var result = service.swipe(1L, new SwipeRequest(2L, "like", "idem-uuid-123"));

        assertThat(result.swipeId()).isEqualTo(55L);
        assertThat(result.matched()).isTrue();
        assertThat(result.match().id()).isEqualTo(88L);
        assertThat(result.match().withUserId()).isEqualTo(2L);

        // Không ghi swipe mới hay tạo match mới
        verify(swipes, never()).saveAndFlush(any());
        verify(matches, never()).createForMutualLike(any(), any());
    }

    @Test
    void idempotentRetryWithConflictingActionThrowsConflict() {
        givenUsers();
        when(swipes.findBySwiperIdAndTargetId(1L, 2L)).thenReturn(Optional.of(
                Swipe.builder().id(55L).action(SwipeAction.LIKE).build()));

        // Trước đó đã 'like', giờ gửi lại 'pass' -> vẫn ném 409 dù có idempotency_key
        assertThatThrownBy(() -> service.swipe(1L, new SwipeRequest(2L, "pass", "idem-uuid-123")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(409));
        verify(swipes, never()).saveAndFlush(any());
    }

    @Test
    void passSkipsReverseLikeLookup() {
        givenUsers();
        var result = service.swipe(1L, new SwipeRequest(2L, "pass"));
        assertThat(result.matched()).isFalse();
        assertThat(result.match()).isNull();
        verify(swipes, never()).existsBySwiperIdAndTargetIdAndAction(any(), any(), any());
        verifyNoInteractions(matches);
    }

    @Test
    void reverseLikeCreatesSortedPairAndAcquiresPairLock() {
        givenUsers();
        when(swipes.existsBySwiperIdAndTargetIdAndAction(1L, 2L, SwipeAction.LIKE)).thenReturn(true);
        when(matches.createForMutualLike(any(), any())).thenAnswer(i -> {
            User first = i.getArgument(0);
            User second = i.getArgument(1);
            assertThat(first.getId()).isEqualTo(1L);
            assertThat(second.getId()).isEqualTo(2L);
            return Match.builder().id(20L).userA(first).userB(second).build();
        });
        var result = service.swipe(2L, new SwipeRequest(1L, "like"));
        assertThat(result.match().id()).isEqualTo(20L);
        assertThat(result.match().withUserId()).isEqualTo(1L);

        // Khóa theo cặp được gọi với pair {2, 1}
        verify(lockManager).acquirePairLock(2L, 1L);
        // Không dùng khóa dòng findByIdForUpdate trên User trong chế độ pair lock
        verify(users, never()).findByIdForUpdate(any());
        // Người dùng được đọc qua findById thông thường
        verify(users).findById(1L);
        verify(users).findById(2L);
        verify(swipes).findBySwiperIdAndTargetId(2L, 1L);
    }

    @Test
    void rowLockStrategyLocksBothUsersInAscendingOrder() {
        givenUsers();
        service.setLockStrategy("row");
        when(swipes.existsBySwiperIdAndTargetIdAndAction(1L, 2L, SwipeAction.LIKE)).thenReturn(true);
        when(matches.createForMutualLike(any(), any())).thenAnswer(i -> {
            User first = i.getArgument(0);
            User second = i.getArgument(1);
            return Match.builder().id(20L).userA(first).userB(second).build();
        });

        var result = service.swipe(2L, new SwipeRequest(1L, "like"));
        assertThat(result.match().id()).isEqualTo(20L);

        // Ở chế độ "row", khóa dòng User theo thứ tự tăng dần
        var order = inOrder(users, swipes);
        order.verify(users).findByIdForUpdate(1L);
        order.verify(users).findByIdForUpdate(2L);
        order.verify(swipes).findBySwiperIdAndTargetId(2L, 1L);
    }

    @Test
    void lockWaitMetricsRecordsDurationForSwipes() {
        givenUsers();
        lockMetrics.reset();
        assertThat(lockMetrics.getTotalSwipes()).isZero();

        service.swipe(1L, new SwipeRequest(2L, "pass"));

        assertThat(lockMetrics.getTotalSwipes()).isEqualTo(1);
        assertThat(lockMetrics.getLastLockWaitMillis()).isGreaterThanOrEqualTo(0);
        assertThat(lockMetrics.getMaxLockWaitMillis()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void failedPairLockStopsSwipeBeforeReadingOrWritingBusinessData() {
        var failure = new PersistenceException("Lock acquisition failed");
        doThrow(failure).when(lockManager).acquirePairLock(1L, 2L);

        assertThatThrownBy(() -> service.swipe(1L, new SwipeRequest(2L, "like"))).isSameAs(failure);

        verifyNoInteractions(users, swipes, matches);
        assertThat(lockMetrics.getTotalSwipes()).isZero();
    }

    @Test
    void pairLockMetricIsFinishedBeforeUserProfilesAreLoaded() {
        givenUsers();
        when(users.findById(1L)).thenAnswer(invocation -> {
            assertThat(lockMetrics.getTotalSwipes()).isEqualTo(1);
            return Optional.of(User.builder().id(1L).build());
        });

        service.swipe(1L, new SwipeRequest(2L, "pass"));

        assertThat(lockMetrics.getTotalSwipes()).isEqualTo(1);
    }

    @Test
    void pairKeySymmetryAndDifferentiation() {
        // A-B và B-A phải có cùng một pair key (dùng chung khóa)
        long keyAB = SwipeLockManager.pairKey(1L, 2L);
        long keyBA = SwipeLockManager.pairKey(2L, 1L);
        assertThat(keyAB).isEqualTo(keyBA);

        // A-B và A-C phải có pair key khác nhau (xử lý song song, không tranh chấp)
        long keyAC = SwipeLockManager.pairKey(1L, 3L);
        assertThat(keyAB).isNotEqualTo(keyAC);

        // B-A và B-C cũng có key khác nhau
        long keyBC = SwipeLockManager.pairKey(2L, 3L);
        assertThat(keyAB).isNotEqualTo(keyBC);
        assertThat(keyAC).isNotEqualTo(keyBC);
    }
}
