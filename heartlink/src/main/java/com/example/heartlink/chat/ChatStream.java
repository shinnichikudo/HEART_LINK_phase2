package com.example.heartlink.chat;

import com.example.heartlink.common.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Holds live message-stream connections for each match on this application instance. */
@Component
public class ChatStream {
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

    private record Connection(Long matchId, Long userId, Instant expiresAt, SseEmitter emitter) {
    }
}