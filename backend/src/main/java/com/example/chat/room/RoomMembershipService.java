package com.example.chat.room;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.chat.auth.CurrentUserService;

@Service
public class RoomMembershipService {

    private final RoomRepository roomRepository;
    private final RoomMemberRepository memberRepository;
    private final CurrentUserService currentUserService;

    public RoomMembershipService(
            RoomRepository roomRepository,
            RoomMemberRepository memberRepository,
            CurrentUserService currentUserService
    ) {
        this.roomRepository = roomRepository;
        this.memberRepository = memberRepository;
        this.currentUserService = currentUserService;
    }

    @Transactional(readOnly = true)
    public MembershipResponse get(Long roomId, String username) {
        requireRoom(roomId);
        var account = currentUserService.requireAccount(username);
        return new MembershipResponse(roomId,
                memberRepository.existsByRoom_IdAndUser_Id(roomId, account.getId()));
    }

    @Transactional
    public void join(Long roomId, String username) {
        requireRoom(roomId);
        var account = currentUserService.requireAccount(username);
        // PostgreSQL handles simultaneous/repeated joins without duplicate rows.
        memberRepository.addIfAbsent(roomId, account.getId());
    }

    @Transactional
    public void leave(Long roomId, String username) {
        requireRoom(roomId);
        var account = currentUserService.requireAccount(username);
        // Removing the membership leaves that person's saved messages in the room.
        memberRepository.remove(roomId, account.getId());
    }

    @Transactional(readOnly = true)
    public void requireMember(Long roomId, String username) {
        // Message reads, sends, and live access all reuse this membership rule.
        if (!get(roomId, username).member()) {
            throw new RoomMembershipRequiredException();
        }
    }

    private void requireRoom(Long roomId) {
        // A missing room is a 404, which is different from a room you have not joined.
        if (!roomRepository.existsById(roomId)) {
            throw new RoomNotFoundException(roomId);
        }
    }
}
