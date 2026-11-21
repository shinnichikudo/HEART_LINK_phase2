package com.example.heartlink.chat;

import com.example.heartlink.common.error.ApiException;
import com.example.heartlink.entity.Match;
import com.example.heartlink.entity.MatchStatus;
import com.example.heartlink.entity.User;
import com.example.heartlink.repository.MatchRepository;
import com.example.heartlink.repository.MessageRepository;
import com.example.heartlink.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatAccessTests {
    private static final Long USER_A_ID = 1L;
    private static final Long USER_B_ID = 2L;
    private static final Long OUTSIDER_ID = 3L;
    private static final Long MATCH_ID = 42L;

    @Mock MatchRepository matches;
    @Mock MessageRepository messages;
    @Mock UserRepository users;
    @Mock ApplicationEventPublisher events;

    private ChatService chat;
    private Match activeMatch;

    @BeforeEach
    void setUp() {
        chat = new ChatService(matches, messages, users,
                Clock.fixed(Instant.parse("2026-11-21T00:00:00Z"), ZoneOffset.UTC), events);
        var userA = User.builder().id(USER_A_ID).email("a@example.com").name("A").build();
        var userB = User.builder().id(USER_B_ID).email("b@example.com").name("B").build();
        activeMatch = Match.builder().id(MATCH_ID).userA(userA).userB(userB)
                .status(MatchStatus.ACTIVE).build();
    }

    @Test
    void activeMatchParticipantsMayOpenTheStream() {
        when(users.existsById(USER_A_ID)).thenReturn(true);
        when(users.existsById(USER_B_ID)).thenReturn(true);
        when(matches.findById(MATCH_ID)).thenReturn(Optional.of(activeMatch));

        assertThatCode(() -> chat.requireActiveMatchParticipant(USER_A_ID, MATCH_ID))
                .doesNotThrowAnyException();
        assertThatCode(() -> chat.requireActiveMatchParticipant(USER_B_ID, MATCH_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void outsiderCannotOpenTheStream() {
        when(users.existsById(OUTSIDER_ID)).thenReturn(true);
        when(matches.findById(MATCH_ID)).thenReturn(Optional.of(activeMatch));

        assertThatThrownBy(() -> chat.requireActiveMatchParticipant(OUTSIDER_ID, MATCH_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> org.assertj.core.api.Assertions.assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void cancelledMatchCannotOpenTheStream() {
        activeMatch.setStatus(MatchStatus.CANCELLED);
        when(users.existsById(USER_A_ID)).thenReturn(true);
        when(matches.findById(MATCH_ID)).thenReturn(Optional.of(activeMatch));

        assertThatThrownBy(() -> chat.requireActiveMatchParticipant(USER_A_ID, MATCH_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> org.assertj.core.api.Assertions.assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void missingMatchCannotOpenTheStream() {
        when(users.existsById(USER_A_ID)).thenReturn(true);
        when(matches.findById(MATCH_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chat.requireActiveMatchParticipant(USER_A_ID, MATCH_ID))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> org.assertj.core.api.Assertions.assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }
}