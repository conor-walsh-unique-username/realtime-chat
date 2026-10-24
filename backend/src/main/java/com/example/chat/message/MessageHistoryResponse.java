package com.example.chat.message;

import java.util.List;

public record MessageHistoryResponse(
        List<MessageResponse> items,
        boolean hasMore,
        // Pass this ID as "before" to fetch the next batch of older messages.
        Long nextBefore
) {
}