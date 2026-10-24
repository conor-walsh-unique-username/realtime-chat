package com.example.chat.room;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateRoomRequest(
        @NotBlank(message = "Room name is required")
        @Size(max = 80, message = "Room name must be at most 80 characters")
        String name
) {
}
