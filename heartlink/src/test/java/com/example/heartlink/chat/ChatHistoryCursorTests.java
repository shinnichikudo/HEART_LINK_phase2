package com.example.heartlink.chat;

import com.example.heartlink.chat.dto.MessagePageResponse;
import com.example.heartlink.chat.dto.MessageResponse;
import com.example.heartlink.common.error.ApiException;
import com.example.heartlink.entity.Match;
import com.example.heartlink.entity.MatchStatus;
import com.example.heartlink.entity.Message;
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
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;`nimport static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatHistoryCursorTests {
    private static final Long USER_ID = 1L;
    private static final Long MATCH_ID = 42L;

    @Mock MatchRepository matches;
    @Mock MessageRepository messages;
    @Mock UserRepository users;
    @Mock ApplicationEventPublisher events;

    private ChatService chat;
    private Match activeMatch;
    private User sender;

    @BeforeEach
    void setUp() {
        chat = new ChatService(matches, messages, users,
                Clock.fixed(Instant.parse("2026-11-21T00:00:00Z"), ZoneOffset.UTC), events);
        sender = User.builder().id(USER_ID).email("a@example.com").name("A").build();
        var recipient = User.builder().id(2L).email("b@example.com").name("B").build();
        activeMatch = Match.builder().id(MATCH_ID).userA(sender).userB(recipient)
                .status(MatchStatus.ACTIVE).build();
    }

    @Test
    void afterReturnsNewMessagesInAscendingOrderAndNextCursor() {
        allowParticipantToRead();
        when(messages.findByMatchIdAndIdGreaterThanOrderByIdAsc(eq(MATCH_ID), eq(10L), any(Pageable.class)))
                .thenReturn(List.of(message(11L), message(12L), message(13L)));

        MessagePageResponse page = chat.list(USER_ID, MATCH_ID, 2, null, 10L);

        assertThat(page.items()).extracting(MessageResponse::id).containsExactly(11L, 12L);
        assertThat(page.nextAfter()).isEqualTo(12L);
        assertThat(page.nextBefore()).isNull();
    }

    @Test
    void beforeStillReturnsOlderMessagesInDescendingOrder() {
        allowParticipantToRead();
        when(messages.findByMatchIdAndIdLessThanOrderByIdDesc(eq(MATCH_ID), eq(10L), any(Pageable.class)))
                .thenReturn(List.of(message(9L), message(8L), message(7L)));

        MessagePageResponse page = chat.list(USER_ID, MATCH_ID, 2, 10L, null);

        assertThat(page.items()).extracting(MessageResponse::id).containsExactly(9L, 8L);
        assertThat(page.nextBefore()).isEqualTo(8L);
        assertThat(page.nextAfter()).isNull();
    }

    @Test
    void rejectsBothCursorsBeforeReadingRepositories() {
        assertThatThrownBy(() -> chat.list(USER_ID, MATCH_ID, 20, 5L, 10L))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        verifyNoInteractions(users, matches, messages);
    }

    @Test
    void afterWithoutMoreMessagesHasNoNextCursor() {
        allowParticipantToRead();
        when(messages.findByMatchIdAndIdGreaterThanOrderByIdAsc(eq(MATCH_ID), eq(10L), any(Pageable.class)))
                .thenReturn(List.of(message(11L), message(12L)));

        MessagePageResponse page = chat.list(USER_ID, MATCH_ID, 2, null, 10L);

        assertThat(page.items()).extracting(MessageResponse::id).containsExactly(11L, 12L);
        assertThat(page.nextAfter()).isNull();
    }

    private void allowParticipantToRead() {
        when(users.existsById(USER_ID)).thenReturn(true);
        when(matches.findById(MATCH_ID)).thenReturn(Optional.of(activeMatch));
    }

    private Message message(Long id) {
        return Message.builder().id(id).match(activeMatch).sender(sender)
                .content("message " + id).createdAt(Instant.parse("2026-11-21T00:00:00Z")).build();
    }
}