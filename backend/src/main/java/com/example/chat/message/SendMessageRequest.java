package com.example.chat.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Extra JSON fields cannot choose the sender, room, or creation time.
@JsonIgnoreProperties(ignoreUnknown = true)
public record SendMessageRequest(
        @NotBlank(message = "Enter a message.")
        @Size(max = 2000, message = "Messages must contain at most 2000 characters.")
        String content
) {
}