package com.example.chat.room;

public class RoomNameConflictException extends RuntimeException {

    public RoomNameConflictException() {
        super("A room with that name already exists. Choose a different name.");
    }
}
