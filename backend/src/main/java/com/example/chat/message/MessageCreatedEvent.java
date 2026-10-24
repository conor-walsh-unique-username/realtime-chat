package com.example.chat.message;

// Carry the public DTO so the listener does not depend on an open JPA session.
public record MessageCreatedEvent(MessageResponse message) {
}