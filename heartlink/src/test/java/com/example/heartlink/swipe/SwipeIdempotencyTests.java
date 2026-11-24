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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SwipeIdempotencyTests {
    private final UserRepository users = mock(UserRepository.class);
    private final SwipeRepository swipes = mock(SwipeRepository.class);
    private final MatchService matches = mock(MatchService.class);
    private final SwipeLockManager locks = mock(SwipeLockManager.class);
    private final SwipeService service = new SwipeService(users, swipes, matches, locks, new SwipeLockMetrics());

    @BeforeEach
    void setUp() {
        for (long id = 1; id <= 3; id++) {
            when(users.findById(id)).thenReturn(Optional.of(User.builder().id(id).build()));
        }
        when(swipes.saveAndFlush(any())).thenAnswer(invocation -> {
            Swipe swipe = invocation.getArgument(0);
            swipe.setId(55L);
            return swipe;
        });
    }

    @Test
    void persistsKeyWithTheOriginalUnmatchedResponse() {
        var response = service.swipe(1L, new SwipeRequest(2L, "like", "key-1"));

        var saved = org.mockito.ArgumentCaptor.forClass(Swipe.class);
        verify(swipes).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getIdempotencyKey()).isEqualTo("key-1");
        assertThat(saved.getValue().getResponseMatchId()).isNull();
        assertThat(response).isEqualTo(new SwipeResponse(55L, false, null));
    }

    @Test
    void persistsTheMatchIdFromTheOriginalResponse() {
        when(swipes.existsBySwiperIdAndTargetIdAndAction(2L, 1L, SwipeAction.LIKE)).thenReturn(true);
        when(matches.createForMutualLike(any(), any())).thenReturn(Match.builder().id(88L).build());

        var response = service.swipe(1L, new SwipeRequest(2L, "like", "key-1"));

        var saved = org.mockito.ArgumentCaptor.forClass(Swipe.class);
        verify(swipes).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getResponseMatchId()).isEqualTo(88L);
        assertThat(response).isEqualTo(new SwipeResponse(55L, true, new SwipeResponse.MatchInfo(88L, 2L)));
    }

    @Test
    void replaysOriginalUnmatchedResponseWithoutLookingUpCurrentMatch() {
        when(swipes.findBySwiperIdAndIdempotencyKey(1L, "key-1"))
                .thenReturn(Optional.of(previous("key-1", null)));

        var response = service.swipe(1L, new SwipeRequest(2L, "like", "key-1"));

        assertThat(response).isEqualTo(new SwipeResponse(55L, false, null));
        verifyNoInteractions(users, matches, locks);
        verify(swipes, never()).saveAndFlush(any());
    }

    @Test
    void rejectsReusingAKeyForADifferentTarget() {
        when(swipes.findBySwiperIdAndIdempotencyKey(1L, "key-1"))
                .thenReturn(Optional.of(previous("key-1", null)));

        assertConflict(() -> service.swipe(1L, new SwipeRequest(3L, "like", "key-1")),
                "IDEMPOTENCY_KEY_REUSED");

        verify(swipes, never()).saveAndFlush(any());
        verifyNoInteractions(locks, matches);
    }

    @Test
    void rechecksCommittedSwipeAfterWaitingForThePairLock() {
        // Key lookup initially sees no committed row; another request commits before we acquire the pair lock.
        when(swipes.findBySwiperIdAndTargetId(1L, 2L))
                .thenReturn(Optional.of(previous("key-1", 88L)));

        var response = service.swipe(1L, new SwipeRequest(2L, "like", "key-1"));

        assertThat(response).isEqualTo(new SwipeResponse(55L, true, new SwipeResponse.MatchInfo(88L, 2L)));
        verify(locks).acquirePairLock(1L, 2L);
        verify(swipes, never()).saveAndFlush(any());
        verifyNoInteractions(matches);
    }

    @Test
    void rejectsChangedActionAfterWaitingForThePairLock() {
        when(swipes.findBySwiperIdAndTargetId(1L, 2L))
                .thenReturn(Optional.of(previous("key-1", null)));

        assertConflict(() -> service.swipe(1L, new SwipeRequest(2L, "pass", "key-1")),
                "IDEMPOTENCY_KEY_REUSED");
        verify(swipes, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"key-0", ""})
    void anotherKeyCannotReplayAnExistingOrLegacySwipe(String originalKey) {
        when(swipes.findBySwiperIdAndTargetId(1L, 2L))
                .thenReturn(Optional.of(previous(originalKey.isEmpty() ? null : originalKey, null)));

        assertConflict(() -> service.swipe(1L, new SwipeRequest(2L, "like", "new-key")), "ALREADY_SWIPED");
        verify(swipes, never()).saveAndFlush(any());
    }

    @Test
    void concurrentReuseAcrossPairsMapsOnlyTheKeyConstraintToAnApiConflict() {
        var databaseFailure = uniqueViolation("uq_swipe_idempotency_key");
        doThrow(databaseFailure).when(swipes).saveAndFlush(any());

        assertConflict(() -> service.swipe(1L, new SwipeRequest(2L, "like", "key-1")),
                "IDEMPOTENCY_KEY_REUSED");
        verifyNoInteractions(matches);
    }

    @Test
    void unrelatedIntegrityFailuresAreNotMisreportedAsKeyReuse() {
        var failure = uniqueViolation("some_other_constraint");
        doThrow(failure).when(swipes).saveAndFlush(any());

        assertThatThrownBy(() -> service.swipe(1L, new SwipeRequest(2L, "like", "key-1")))
                .isSameAs(failure);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "has space", "line\nbreak", "é"})
    void rejectsInvalidKeysBeforeDatabaseAccess(String key) {
        assertValidationFailure(() -> service.swipe(1L, new SwipeRequest(2L, "like"), key));
        verifyNoInteractions(users, swipes, matches, locks);
    }

    @Test
    void rejectsOversizedKeyBeforeDatabaseAccess() {
        assertValidationFailure(() -> service.swipe(1L, new SwipeRequest(2L, "like"), "k".repeat(129)));
        verifyNoInteractions(users, swipes, matches, locks);
    }

    @Test
    void rejectsDifferentHeaderAndBodyKeysBeforeDatabaseAccess() {
        assertValidationFailure(() -> service.swipe(1L, new SwipeRequest(2L, "like", "body-key"), "header-key"));
        verifyNoInteractions(users, swipes, matches, locks);
    }

    @Test
    void acceptsMatchingHeaderAndBodyKeysAndTheMaximumLength() {
        String key = "k".repeat(128);

        var response = service.swipe(1L, new SwipeRequest(2L, "pass", key), key);

        assertThat(response.swipeId()).isEqualTo(55L);
        verify(swipes).findBySwiperIdAndIdempotencyKey(1L, key);
    }

    @Test
    void theSameKeyCanBeUsedByDifferentUsers() {
        when(swipes.findBySwiperIdAndIdempotencyKey(1L, "shared-key"))
                .thenReturn(Optional.of(previous("shared-key", null)));

        var response = service.swipe(2L, new SwipeRequest(3L, "pass"), "shared-key");

        assertThat(response.swipeId()).isEqualTo(55L);
        verify(swipes).findBySwiperIdAndIdempotencyKey(2L, "shared-key");
        verify(swipes).saveAndFlush(any());
    }

    private Swipe previous(String key, Long matchId) {
        return Swipe.builder().id(55L).swiper(User.builder().id(1L).build())
                .target(User.builder().id(2L).build()).action(SwipeAction.LIKE)
                .idempotencyKey(key).responseMatchId(matchId).build();
    }

    private DataIntegrityViolationException uniqueViolation(String constraint) {
        return new DataIntegrityViolationException("Duplicate key",
                new ConstraintViolationException("Duplicate key", new SQLException("Duplicate key", "23505"), constraint));
    }

    private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, exception -> {
            assertThat(exception.getStatus().value()).isEqualTo(409);
            assertThat(exception.getCode()).isEqualTo(code);
        });
    }

    private void assertValidationFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, exception -> {
            assertThat(exception.getStatus().value()).isEqualTo(400);
            assertThat(exception.getCode()).isEqualTo("VALIDATION_ERROR");
        });
    }
}
