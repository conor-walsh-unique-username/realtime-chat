package com.example.chat.auth;

import java.time.Instant;

import com.example.chat.user.ChatUser;

// A DTO is just the data we send back. Leaving out the hash keeps it out of account JSON.
public record UserResponse(Long id, String username, Instant createdAt) {

    public static UserResponse from(ChatUser user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getCreatedAt());
    }
}
