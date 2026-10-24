package com.example.chat.message;

import java.time.Instant;

public record MessageResponse(
        Long id,
        Long roomId,
        Long senderId,
        String senderUsername,
        String content,
        Instant createdAt
) {

    public static MessageResponse from(ChatMessage message) {
        // The DTO exposes a sender's public identity and keeps account internals private.
        return new MessageResponse(
                message.getId(),
                message.getRoom().getId(),
                message.getSender().getId(),
                message.getSender().getUsername(),
                message.getContent(),
                message.getCreatedAt()
        );
    }
}