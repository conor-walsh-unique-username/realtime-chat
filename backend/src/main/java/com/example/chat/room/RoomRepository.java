package com.example.chat.room;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomRepository extends JpaRepository<Room, Long> {

    // Spring generates this lookup from the name; JpaRepository already provides save/findById.
    boolean existsByNameIgnoreCase(String name);
}
