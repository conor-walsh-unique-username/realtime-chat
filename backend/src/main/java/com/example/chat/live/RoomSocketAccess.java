package com.example.chat.live;

import com.example.chat.room.RoomMembershipRequiredException;
import com.example.chat.room.RoomMembershipService;
import com.example.chat.room.RoomNotFoundException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RoomSocketAccess {

    private final RoomMembershipService membershipService;

    public RoomSocketAccess(RoomMembershipService membershipService) {
        this.membershipService = membershipService;
    }

    // Delivery happens after the sending transaction commits; read access in a fresh transaction.
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public boolean canReceive(RoomSocketIdentity identity) {
        try {
            var session = identity.httpSession();
            // Socket traffic does not refresh HTTP activity, so check the session timeout here too.
            int timeoutSeconds = session.getMaxInactiveInterval();
            if (timeoutSeconds > 0 && System.currentTimeMillis() - session.getLastAccessedTime()
                    >= timeoutSeconds * 1000L) {
                return false;
            }
            // Re-read the current login: the original handshake is not a permanent permission slip.
            var value = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            if (!(value instanceof SecurityContext context)) {
                return false;
            }
            var authentication = context.getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()
                    || authentication instanceof AnonymousAuthenticationToken
                    || !identity.username().equals(authentication.getName())) {
                return false;
            }
            // Someone may have left the room since opening this socket.
            membershipService.requireMember(identity.roomId(), identity.username());
            return true;
        } catch (IllegalStateException | RoomNotFoundException | RoomMembershipRequiredException
                 | ResponseStatusException exception) {
            // An invalidated session, removed account, or removed membership loses access.
            return false;
        }
    }
}
