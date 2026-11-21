package com.example.heartlink.chat;

import com.example.heartlink.chat.dto.MessageResponse;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ChatStreamTests {
    private static final Instant EXPIRES_AT = Instant.parse("2026-11-22T00:00:00Z");

    @Test
    void messageIsSentOnlyToTheRecipientOnThatMatch() throws Exception {
        var stream = new ChatStream(Clock.fixed(Instant.parse("2026-11-21T00:00:00Z"), ZoneOffset.UTC));
        var sender = mock(SseEmitter.class);
        var recipient = mock(SseEmitter.class);
        var recipientSecondDevice = mock(SseEmitter.class);
        var otherUser = mock(SseEmitter.class);
        var otherMatch = mock(SseEmitter.class);

        stream.register(42L, 1L, EXPIRES_AT, sender);
        stream.register(42L, 2L, EXPIRES_AT, recipient);
        stream.register(42L, 2L, EXPIRES_AT, recipientSecondDevice);
        stream.register(42L, 3L, EXPIRES_AT, otherUser);
        stream.register(43L, 2L, EXPIRES_AT, otherMatch);
        clearInvocations(sender, recipient, recipientSecondDevice, otherUser, otherMatch);

        var message = new MessageResponse(100L, 1L, "Hello", Instant.parse("2026-11-21T00:00:00Z"));
        stream.onMessageCreated(new MessageCreated(42L, 2L, message));

        verify(recipient).send(any(SseEmitter.SseEventBuilder.class));
        verify(recipientSecondDevice).send(any(SseEmitter.SseEventBuilder.class));
        verify(sender, never()).send(any(SseEmitter.SseEventBuilder.class));
        verify(otherUser, never()).send(any(SseEmitter.SseEventBuilder.class));
        verify(otherMatch, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void messageListenerRunsAfterTransactionCommit() throws Exception {
        var listener = ChatStream.class
                .getMethod("onMessageCreated", MessageCreated.class)
                .getAnnotation(TransactionalEventListener.class);

        assertThat(listener).isNotNull();
        assertThat(listener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }
}