package com.example.chat.room;

import java.util.List;

public record RoomPageResponse(
        List<RoomResponse> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {
}
