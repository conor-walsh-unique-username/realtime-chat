package com.example.chat.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

// The type arguments mean this repository stores ChatUser objects with Long IDs.
public interface ChatUserRepository extends JpaRepository<ChatUser, Long> {
    // Optional makes the "no account found" case something the caller has to handle.
    Optional<ChatUser> findByUsername(String username);

    boolean existsByUsername(String username);
}
