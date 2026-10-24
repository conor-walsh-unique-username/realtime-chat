package com.example.chat.message;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    // Fetch the sender in the same query so each DTO needs no extra user lookup.
    @Query("""
            select message from ChatMessage message
            join fetch message.sender
            where message.room.id = :roomId
            order by message.id desc
            """)
    List<ChatMessage> findLatestInRoom(@Param("roomId") Long roomId, Pageable pageable);

    // "before" is exclusive: the last ID on one page is not repeated on the next.
    @Query("""
            select message from ChatMessage message
            join fetch message.sender
            where message.room.id = :roomId and message.id < :beforeId
            order by message.id desc
            """)
    List<ChatMessage> findOlderInRoom(
            @Param("roomId") Long roomId,
            @Param("beforeId") Long beforeId,
            Pageable pageable
    );
}
