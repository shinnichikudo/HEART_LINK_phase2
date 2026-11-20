package com.example.heartlink.chat;

import com.example.heartlink.chat.dto.MessageResponse;

/** Immutable event raised when a chat message has been saved. */
public record MessageCreated(Long matchId, Long recipientId, MessageResponse message) {
}