package com.example.chat.room;

import java.time.Instant;

public record RoomResponse(Long id, String name, Instant createdAt) {

    static RoomResponse from(Room room) {
        return new RoomResponse(room.getId(), room.getName(), room.getCreatedAt());
    }
}
