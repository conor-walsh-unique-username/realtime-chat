package com.example.chat.room;

import java.security.Principal;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rooms/{roomId}/members/me")
// "me" always comes from the logged-in principal, so a caller cannot pick another user ID.
public class RoomMembershipController {

    private final RoomMembershipService membershipService;

    public RoomMembershipController(RoomMembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @GetMapping
    public MembershipResponse get(@PathVariable("roomId") Long roomId, Principal principal) {
        return membershipService.get(roomId, principal.getName());
    }

    @PostMapping
    public ResponseEntity<Void> join(@PathVariable("roomId") Long roomId, Principal principal) {
        membershipService.join(roomId, principal.getName());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> leave(@PathVariable("roomId") Long roomId, Principal principal) {
        membershipService.leave(roomId, principal.getName());
        return ResponseEntity.noContent().build();
    }
}
