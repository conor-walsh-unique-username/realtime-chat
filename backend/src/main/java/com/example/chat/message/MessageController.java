package com.example.chat.message;

import java.security.Principal;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

@RestController
@RequestMapping("/api/rooms/{roomId}/messages")
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MessageResponse send(
            @PathVariable("roomId") Long roomId,
            @Valid @RequestBody SendMessageRequest request,
            Principal principal
    ) {
        // Spring Security supplies the authenticated account's name.
        return messageService.send(roomId, request.content(), principal.getName());
    }

    @GetMapping
    // Leave "before" out for the latest page, or pass a saved ID to go further back.
    public MessageHistoryResponse history(
            @PathVariable("roomId") Long roomId,
            @RequestParam(name = "before", required = false) @Positive Long before,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(50) int size,
            Principal principal
    ) {
        return messageService.history(roomId, before, size, principal.getName());
    }
}
