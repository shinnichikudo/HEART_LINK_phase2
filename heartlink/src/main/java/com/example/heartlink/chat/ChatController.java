package com.example.heartlink.chat;

import com.example.heartlink.chat.dto.*;
import com.example.heartlink.common.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/api/v1/matches/{matchId}/messages")
@SecurityRequirement(name = "bearerAuth")
public class ChatController {
    private final ChatService chat;
    private final ChatStream stream;

    public ChatController(ChatService chat, ChatStream stream) {
        this.chat = chat;
        this.stream = stream;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Live chat messages",
            description = "Authenticated SSE for a match. Event name: message.created. Reconnect by loading message history.")
    public ResponseEntity<SseEmitter> stream(
            @Parameter(hidden = true) @RequestAttribute(CurrentUser.ID) Long userId,
            @Parameter(hidden = true) @RequestAttribute(CurrentUser.EXPIRES_AT) Instant expiresAt,
            @PathVariable @Min(1) Long matchId) {
        chat.requireActiveMatchParticipant(userId, matchId);
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache, no-transform")
                .header("X-Accel-Buffering", "no")
                .body(stream.subscribe(matchId, userId, expiresAt));
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Send a text message", description = "Only participants of an active match can send messages. Content: 1-2000 characters.")
    public MessageResponse send(
            @Parameter(hidden = true) @RequestAttribute(CurrentUser.ID) Long userId,
            @PathVariable @Min(1) Long matchId,
            @Valid @RequestBody SendMessageRequest request) {
        return chat.send(userId, matchId, request);
    }

    @GetMapping
    @Operation(summary = "Message history", description = "Newest ID first by default. Use before for older messages or after for missed newer messages; the cursors cannot be combined.")
    public MessagePageResponse list(
            @Parameter(hidden = true) @RequestAttribute(CurrentUser.ID) Long userId,
            @PathVariable @Min(1) Long matchId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) Long before,
            @RequestParam(required = false) Long after) {
        return chat.list(userId, matchId, limit, before, after);
    }
}
