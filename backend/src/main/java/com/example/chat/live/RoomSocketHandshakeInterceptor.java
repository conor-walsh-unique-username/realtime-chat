package com.example.chat.live;

import com.example.chat.room.RoomMembershipRequiredException;
import com.example.chat.room.RoomMembershipService;
import com.example.chat.room.RoomNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.regex.Pattern;

@Component
public class RoomSocketHandshakeInterceptor implements HandshakeInterceptor {

    private static final Pattern PATH = Pattern.compile("^/ws/rooms/([1-9][0-9]*)$");
    private final RoomMembershipService membershipService;

    public RoomSocketHandshakeInterceptor(RoomMembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Map<String, Object> attributes
    ) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        // Reuse an existing login session; false means do not create one for this request.
        var session = servletRequest.getServletRequest().getSession(false);
        var principal = request.getPrincipal();
        if (session == null || principal == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        // The wildcard route still needs a valid positive room ID before we can check access.
        var match = PATH.matcher(request.getURI().getPath());
        if (!match.matches()) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        }
        try {
            Long roomId = Long.valueOf(match.group(1));
            membershipService.requireMember(roomId, principal.getName());
            // Keep only the trusted room/account/session needed for later delivery checks.
            attributes.put(RoomSocketIdentity.ATTRIBUTE,
                    new RoomSocketIdentity(roomId, principal.getName(), session));
            return true;
        } catch (NumberFormatException exception) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
        } catch (RoomNotFoundException exception) {
            response.setStatusCode(HttpStatus.NOT_FOUND);
        } catch (RoomMembershipRequiredException exception) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
        } catch (ResponseStatusException exception) {
            response.setStatusCode(exception.getStatusCode());
        }
        return false;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Exception exception
    ) {
    }
}
