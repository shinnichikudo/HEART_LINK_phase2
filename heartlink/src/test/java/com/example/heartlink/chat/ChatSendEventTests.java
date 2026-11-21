package com.example.heartlink.chat;

import com.example.heartlink.chat.dto.SendMessageRequest;
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
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatSendEventTests {
    private static final Long USER_A_ID = 1L;
    private static final Long USER_B_ID = 2L;
    private static final Long MATCH_ID = 42L;

    @Mock MatchRepository matches;
    @Mock MessageRepository messages;
    @Mock UserRepository users;
    @Mock ApplicationEventPublisher events;

    private ChatService chat;

    @BeforeEach
    void setUp() {
        chat = new ChatService(matches, messages, users,
                Clock.fixed(Instant.parse("2026-11-21T00:00:00Z"), ZoneOffset.UTC), events);
        var userA = User.builder().id(USER_A_ID).email("a@example.com").name("A").build();
        var userB = User.builder().id(USER_B_ID).email("b@example.com").name("B").build();
        var match = Match.builder().id(MATCH_ID).userA(userA).userB(userB)
                .status(MatchStatus.ACTIVE).build();

        when(users.existsById(USER_A_ID)).thenReturn(true);
        when(matches.findByIdForMessage(MATCH_ID)).thenReturn(Optional.of(match));
        when(messages.saveAndFlush(any(Message.class))).thenAnswer(invocation -> {
            var message = (Message) invocation.getArgument(0);
            return Message.builder().id(100L).match(match).sender(userA)
                    .content(message.getContent()).createdAt(message.getCreatedAt()).build();
        });
    }

    @Test
    void publishesTheSavedMessageForTheOtherParticipant() {
        var response = chat.send(USER_A_ID, MATCH_ID, new SendMessageRequest("Hello"));

        assertThat(response.id()).isEqualTo(100L);
        verify(events).publishEvent(new MessageCreated(MATCH_ID, USER_B_ID, response));
    }

    @Test
    void doesNotPublishWhenSavingTheMessageFails() {
        when(messages.saveAndFlush(any(Message.class)))
                .thenThrow(new DataIntegrityViolationException("database rejected message"));

        assertThatThrownBy(() -> chat.send(USER_A_ID, MATCH_ID, new SendMessageRequest("Hello")))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(events, never()).publishEvent(any(Object.class));
    }
}