package com.example.chat.room;

import java.net.URI;
import java.security.Principal;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    @PostMapping
    public ResponseEntity<RoomResponse> create(
            @Valid @RequestBody CreateRoomRequest request, Principal principal
    ) {
        RoomResponse room = roomService.create(request.name(), principal.getName());
        // A 201 response includes both the new room and a Location header pointing to it.
        return ResponseEntity.created(URI.create("/api/rooms/" + room.id())).body(room);
    }

    @GetMapping
    // Page numbers start at zero. The bounds also stop a client requesting every room at once.
    public RoomPageResponse list(
            @RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(50) int size
    ) {
        return roomService.list(page, size);
    }

    @GetMapping("/{roomId}")
    public RoomResponse get(@PathVariable("roomId") Long roomId) {
        return roomService.get(roomId);
    }
}
