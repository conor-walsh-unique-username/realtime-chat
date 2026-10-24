package com.example.chat.live;

import jakarta.servlet.http.HttpSession;

// Set by the server during the HTTP handshake, using the authenticated session.
public record RoomSocketIdentity(Long roomId, String username, HttpSession httpSession) {

    public static final String ATTRIBUTE = RoomSocketIdentity.class.getName();
}