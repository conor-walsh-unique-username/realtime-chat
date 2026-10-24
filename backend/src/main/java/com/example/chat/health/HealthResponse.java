package com.example.chat.health;

// A record is an immutable data carrier. Spring converts this response to JSON.
public record HealthResponse(String status, String application) {
}
