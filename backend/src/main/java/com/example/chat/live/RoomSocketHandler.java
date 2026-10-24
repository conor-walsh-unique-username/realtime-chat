package com.example.chat.live;

import com.example.chat.message.MessageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RoomSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RoomSocketHandler.class);
    // HTTP sends and socket callbacks can touch this registry from different threads.
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final RoomSocketAccess access;
    private final ObjectMapper objectMapper;

    public RoomSocketHandler(RoomSocketAccess access, ObjectMapper objectMapper) {
        this.access = access;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        var value = session.getAttributes().get(RoomSocketIdentity.ATTRIBUTE);
        if (!(value instanceof RoomSocketIdentity identity) || !access.canReceive(identity)) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Room access required."));
            return;
        }
        // Serialize socket writes, with a 5-second send limit and a 64 KiB buffer limit.
        var safeSession = new ConcurrentWebSocketSessionDecorator(session, 5000, 64 * 1024);
        var connection = new Connection(identity, safeSession);
        // Registration and the first frame share a lock so "ready" precedes live events.
        synchronized (connection) {
            connections.put(session.getId(), connection);
            try {
                safeSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of(
                        "type", "ready", "roomId", identity.roomId()
                ))));
            } catch (IOException | RuntimeException exception) {
                close(connection, CloseStatus.SERVER_ERROR);
            }
        }
    }

    public void broadcast(MessageResponse message) {
        final TextMessage frame;
        try {
            // Every recipient gets the same JSON, so build the frame once for this message.
            frame = new TextMessage(objectMapper.writeValueAsString(Map.of(
                    "type", "message.created", "message", message
            )));
        } catch (RuntimeException exception) {
            log.error("Could not serialize live message {}", message.id(), exception);
            return;
        }
        for (var connection : connections.values()) {
            if (!connection.identity().roomId().equals(message.roomId())) {
                continue;
            }
            synchronized (connection) {
                try {
                    // Login or membership may have changed since the connection was accepted.
                    if (!connection.session().isOpen() || !access.canReceive(connection.identity())) {
                        close(connection, CloseStatus.POLICY_VIOLATION.withReason("Room access ended."));
                        continue;
                    }
                    connection.session().sendMessage(frame);
                } catch (IOException | RuntimeException exception) {
                    // A live-delivery failure must not undo or misreport an already committed send.
                    log.warn("Live delivery failed for socket {}", connection.session().getId(), exception);
                    close(connection, CloseStatus.SERVER_ERROR);
                }
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        // The socket receives updates; REST owns authenticated, CSRF-protected writes.
        session.close(CloseStatus.POLICY_VIOLATION.withReason("Send messages through the REST API."));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        connections.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws IOException {
        connections.remove(session.getId());
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    private void close(Connection connection, CloseStatus status) {
        connections.remove(connection.session().getId(), connection);
        try {
            if (connection.session().isOpen()) {
                connection.session().close(status);
            }
        } catch (IOException | RuntimeException exception) {
            log.debug("Socket close failed for {}", connection.session().getId(), exception);
        }
    }

    private record Connection(RoomSocketIdentity identity, WebSocketSession session) {
    }
}
