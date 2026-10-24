package com.example.chat.room;

import java.time.Instant;

import com.example.chat.user.ChatUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

// Membership is its own row so we can track when someone joined and remove it on leaving.
@Entity
@Table(name = "room_members", uniqueConstraints = @UniqueConstraint(
        name = "uq_room_members_room_user", columnNames = {"room_id", "user_id"}
))
public class RoomMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // LAZY lets Hibernate load the linked entity only when its details are needed.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private ChatUser user;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    protected RoomMember() {
    }

    public RoomMember(Room room, ChatUser user, Instant joinedAt) {
        this.room = room;
        this.user = user;
        this.joinedAt = joinedAt;
    }

    public Long getId() {
        return id;
    }

    public Room getRoom() {
        return room;
    }

    public ChatUser getUser() {
        return user;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }
}
