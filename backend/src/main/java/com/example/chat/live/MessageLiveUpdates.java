package com.example.chat.live;

import com.example.chat.message.MessageCreatedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class MessageLiveUpdates {

    private final RoomSocketHandler handler;

    public MessageLiveUpdates(RoomSocketHandler handler) {
        this.handler = handler;
    }

    // Wait until the database says the save worked. A rollback must not produce a live message.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageCreated(MessageCreatedEvent event) {
        handler.broadcast(event.message());
    }
}
