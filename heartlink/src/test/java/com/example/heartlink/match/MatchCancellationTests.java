package com.example.heartlink.match;

import com.example.heartlink.common.error.ApiException;
import com.example.heartlink.entity.MatchStatus;
import com.example.heartlink.notification.NotificationService;
import com.example.heartlink.repository.MatchRepository;
import com.example.heartlink.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MatchCancellationTests {
    private static final Long USER_ID = 1L;
    private static final Long MATCH_ID = 42L;

    @Mock MatchRepository matches;
    @Mock UserRepository users;
    @Mock NotificationService notifications;
    @Mock ApplicationEventPublisher events;

    private MatchService service;

    @BeforeEach
    void setUp() {
        service = new MatchService(matches, users, notifications, events);
        when(users.existsById(USER_ID)).thenReturn(true);
    }

    @Test
    void publishesCancellationOnlyAfterTheMatchWasUpdated() {
        when(matches.cancelForUser(MATCH_ID, USER_ID, MatchStatus.ACTIVE, MatchStatus.CANCELLED))
                .thenReturn(1);

        service.cancel(USER_ID, MATCH_ID);

        verify(events).publishEvent(new MatchCancelled(MATCH_ID));
    }

    @Test
    void doesNotPublishCancellationWhenTheUserIsNotAMember() {
        when(matches.cancelForUser(MATCH_ID, USER_ID, MatchStatus.ACTIVE, MatchStatus.CANCELLED))
                .thenReturn(0);
        when(matches.existsByIdAndStatus(MATCH_ID, MatchStatus.ACTIVE)).thenReturn(true);

        assertThatThrownBy(() -> service.cancel(USER_ID, MATCH_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> org.assertj.core.api.Assertions.assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        verify(events, never()).publishEvent(new MatchCancelled(MATCH_ID));
    }
}