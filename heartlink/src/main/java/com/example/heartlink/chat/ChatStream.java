package com.example.heartlink.chat;

import com.example.heartlink.common.error.ApiException;
import jakarta.annotation.PreDestroy;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
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
        register(matchId, userId, expiresAt, emitter);
        return emitter;
    }

    void register(Long matchId, Long userId, Instant expiresAt, SseEmitter emitter) {
        var connection = new Connection(matchId, userId, expiresAt, emitter);
        emitter.onCompletion(() -> remove(connection));
        emitter.onTimeout(() -> close(connection));
        emitter.onError(error -> remove(connection));
        connections.compute(matchId, (id, current) -> {
            var clients = current == null ? ConcurrentHashMap.<Connection>newKeySet() : current;
            clients.add(connection);
            return clients;
        });
        send(connection, SseEmitter.event().comment("connected").reconnectTime(3000));
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
                send(connection, SseEmitter.event()
                        .id(event.message().id().toString())
                        .name("message.created")
                        .data(event.message()));
            }
        }
    }

    @Scheduled(fixedDelay = 15000)
    public void heartbeat() {
        connections.values().forEach(clients -> clients.forEach(connection ->
                send(connection, SseEmitter.event().comment("heartbeat"))));
    }

    private void send(Connection connection, SseEmitter.SseEventBuilder event) {
        if (!clock.instant().isBefore(connection.expiresAt())) {
            close(connection);
            return;
        }
        try {
            connection.emitter().send(event);
        } catch (IOException | IllegalStateException exception) {
            log.debug("Closing chat stream for user {}", connection.userId(), exception);
            close(connection);
        } catch (RuntimeException exception) {
            log.warn("Chat event delivery failed for user {}", connection.userId(), exception);
            close(connection);
        }
    }

    private void remove(Connection connection) {
        connections.computeIfPresent(connection.matchId(), (id, clients) -> {
            clients.remove(connection);
            return clients.isEmpty() ? null : clients;
        });
    }

    private void close(Connection connection) {
        remove(connection);
        connection.emitter().complete();
    }

    @PreDestroy
    public void shutdown() {
        connections.values().forEach(clients -> clients.forEach(this::close));
    }

    /** Close async requests before the web server begins its graceful-shutdown wait. */
    @EventListener(ContextClosedEvent.class)
    public void onContextClosed() {
        shutdown();
    }

    private record Connection(Long matchId, Long userId, Instant expiresAt, SseEmitter emitter) {
    }
}