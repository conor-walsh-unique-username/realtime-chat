package com.example.chat.room;

public class RoomNotFoundException extends RuntimeException {

    public RoomNotFoundException(Long id) {
        super("Room " + id + " was not found.");
    }
}
