package com.example.chat.message;

import com.example.chat.auth.CurrentUserService;
import com.example.chat.room.RoomMembershipService;
import com.example.chat.room.RoomRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class MessageService {

    private final ChatMessageRepository messageRepository;
    private final RoomRepository roomRepository;
    private final RoomMembershipService membershipService;
    private final CurrentUserService currentUserService;
    private final ApplicationEventPublisher events;

    public MessageService(
            ChatMessageRepository messageRepository,
            RoomRepository roomRepository,
            RoomMembershipService membershipService,
            CurrentUserService currentUserService,
            ApplicationEventPublisher events
    ) {
        this.messageRepository = messageRepository;
        this.roomRepository = roomRepository;
        this.membershipService = membershipService;
        this.currentUserService = currentUserService;
        this.events = events;
    }

    @Transactional
    public MessageResponse send(Long roomId, String content, String username) {
        // Membership and sender identity are checked for every write.
        membershipService.requireMember(roomId, username);
        var sender = currentUserService.requireAccount(username);
        // Membership already checked the room exists; a reference lets us set its foreign key.
        var room = roomRepository.getReferenceById(roomId);
        // Keep the original spacing and line breaks. Validation has already rejected blank input.
        var message = new ChatMessage(room, sender, content, Instant.now());
        var response = MessageResponse.from(messageRepository.save(message));
        // Publish inside this transaction; the listener waits for a successful commit.
        events.publishEvent(new MessageCreatedEvent(response));
        return response;
    }

    @Transactional(readOnly = true)
    public MessageHistoryResponse history(Long roomId, Long before, int size, String username) {
        membershipService.requireMember(roomId, username);

        // One extra row tells us whether another page exists, without counting all messages.
        var limit = PageRequest.of(0, size + 1);
        var messages = before == null
                ? messageRepository.findLatestInRoom(roomId, limit)
                : messageRepository.findOlderInRoom(roomId, before, limit);
        boolean hasMore = messages.size() > size;
        var items = messages.stream().limit(size).map(MessageResponse::from).toList();
        // Results are newest first, so the last item is the starting point for the older page.
        Long nextBefore = hasMore ? items.get(items.size() - 1).id() : null;
        return new MessageHistoryResponse(items, hasMore, nextBefore);
    }
}
