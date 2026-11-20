package com.example.heartlink.chat;

import com.example.heartlink.common.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Holds live message-stream connections for each match on this application instance. */
@Component
public class ChatStream {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChatStream.class);
    private final ConcurrentHashMap<Long, Set<Connection>> connections = new ConcurrentHashMap<>();
    private final Clock clock;

    public ChatStream(Clock clock) {
        this.clock = clock;
    }

    public SseEmitter subscribe(Long matchId, Long userId, Instant expiresAt) {
        long timeout = Duration.between(clock.instant(), expiresAt).toMillis();
        if (timeout <= 0) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Access token has expired");
        }

        var emitter = new SseEmitter(timeout);
        var connection = new Connection(matchId, userId, expiresAt, emitter);
        connections.compute(matchId, (id, current) -> {
            var clients = current == null ? ConcurrentHashMap.<Connection>newKeySet() : current;
            clients.add(connection);
            return clients;
        });
        return emitter;
    }

    /** Delivers a committed message only to the other participant of the match. */
    @TransactionalEventListener
    public void onMessageCreated(MessageCreated event) {
        var clients = connections.get(event.matchId());
        if (clients == null) {
            return;
        }
        for (var connection : clients) {
            if (connection.userId().equals(event.recipientId())) {
                sendMessage(connection, event);
            }
        }
    }

    private void sendMessage(Connection connection, MessageCreated event) {
        try {
            connection.emitter().send(SseEmitter.event()
                    .id(event.message().id().toString())
                    .name("message.created")
                    .data(event.message()));
        } catch (IOException | IllegalStateException exception) {
            log.debug("Closing chat stream for user {}", connection.userId(), exception);
            connection.emitter().completeWithError(exception);
        } catch (RuntimeException exception) {
            log.warn("Chat event delivery failed for user {}", connection.userId(), exception);
            connection.emitter().completeWithError(exception);
        }
    }
    private record Connection(Long matchId, Long userId, Instant expiresAt, SseEmitter emitter) {
    }
}