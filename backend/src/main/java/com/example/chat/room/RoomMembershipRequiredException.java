package com.example.chat.room;

public class RoomMembershipRequiredException extends RuntimeException {

    public RoomMembershipRequiredException() {
        super("Join this room before accessing its messages.");
    }
}