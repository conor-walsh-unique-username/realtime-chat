package com.example.chat.room;

import java.time.Instant;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.chat.auth.CurrentUserService;

@Service
public class RoomService {

    private final RoomRepository roomRepository;
    private final RoomMemberRepository memberRepository;
    private final CurrentUserService currentUserService;

    public RoomService(
            RoomRepository roomRepository,
            RoomMemberRepository memberRepository,
            CurrentUserService currentUserService
    ) {
        this.roomRepository = roomRepository;
        this.memberRepository = memberRepository;
        this.currentUserService = currentUserService;
    }

    // The room and its creator's membership should either both save or both roll back.
    @Transactional
    public RoomResponse create(String requestedName, String username) {
        var creator = currentUserService.requireAccount(username);
        String name = requestedName.strip();

        if (roomRepository.existsByNameIgnoreCase(name)) {
            throw new RoomNameConflictException();
        }

        try {
            // Flush now so a concurrent duplicate reaches our conflict handler.
            Room saved = roomRepository.saveAndFlush(new Room(name, Instant.now()));
            // Creating a room also joins you, so you can send a message straight away.
            memberRepository.save(new RoomMember(saved, creator, Instant.now()));
            return RoomResponse.from(saved);
        } catch (DataIntegrityViolationException exception) {
            if (isRoomNameConflict(exception)) {
                throw new RoomNameConflictException();
            }
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public RoomPageResponse list(int page, int size) {
        // Sorting by ID puts newer rooms first and gives each page a consistent ordering.
        var rooms = roomRepository.findAll(
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"))
        );
        return new RoomPageResponse(
                rooms.getContent().stream().map(RoomResponse::from).toList(),
                rooms.getNumber(),
                rooms.getSize(),
                rooms.getTotalElements(),
                rooms.getTotalPages()
        );
    }

    @Transactional(readOnly = true)
    public RoomResponse get(Long id) {
        Room room = roomRepository.findById(id)
                .orElseThrow(() -> new RoomNotFoundException(id));
        return RoomResponse.from(room);
    }

    private boolean isRoomNameConflict(Throwable exception) {
        // Follow the wrapped cause down to the database's case-insensitive name constraint.
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "uq_chat_rooms_name_lower".equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
