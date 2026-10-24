package com.example.chat.room;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RoomMemberRepository extends JpaRepository<RoomMember, Long> {

    // Spring reads this method name as a query through room.id and user.id.
    boolean existsByRoom_IdAndUser_Id(Long roomId, Long userId);

    // @Modifying marks a write query. ON CONFLICT makes two overlapping joins safe.
    @Modifying
    @Query(value = """
            INSERT INTO room_members (room_id, user_id, joined_at)
            VALUES (:roomId, :userId, CURRENT_TIMESTAMP)
            ON CONFLICT (room_id, user_id) DO NOTHING
            """, nativeQuery = true)
    int addIfAbsent(@Param("roomId") Long roomId, @Param("userId") Long userId);

    // This query uses Java entity/field names (JPQL); the INSERT above uses real SQL names.
    @Modifying
    @Query("delete from RoomMember membership where membership.room.id = :roomId and membership.user.id = :userId")
    int remove(@Param("roomId") Long roomId, @Param("userId") Long userId);
}
