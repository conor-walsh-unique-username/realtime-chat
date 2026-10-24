package com.example.chat.live;

import com.example.chat.room.RoomMembershipRequiredException;
import com.example.chat.room.RoomMembershipService;
import com.example.chat.room.RoomNotFoundException;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Fake the session and membership lookup to check access rules without a server or database.
class RoomSocketAccessTest {

    private final RoomMembershipService memberships = mock(RoomMembershipService.class);
    private final HttpSession session = mock(HttpSession.class);
    private final RoomSocketAccess access = new RoomSocketAccess(memberships);
    private final RoomSocketIdentity identity = new RoomSocketIdentity(7L, "alice", session);

    @BeforeEach
    void authenticatedSession() {
        // Start with a valid Alice session, then change one condition in each test.
        when(session.getMaxInactiveInterval()).thenReturn(1800);
        when(session.getLastAccessedTime()).thenReturn(System.currentTimeMillis());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("alice", "unused", List.of()));
        when(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .thenReturn(context);
    }

    @Test
    void aCurrentMemberWithTheOriginalAuthenticatedSessionCanReceive() {
        assertThat(access.canReceive(identity)).isTrue();
        verify(memberships).requireMember(7L, "alice");
    }

    @Test
    void leavingTheRoomRevokesDelivery() {
        doThrow(new RoomMembershipRequiredException()).when(memberships).requireMember(7L, "alice");
        assertThat(access.canReceive(identity)).isFalse();
    }

    @Test
    void aRemovedRoomRevokesDelivery() {
        doThrow(new RoomNotFoundException(7L)).when(memberships).requireMember(7L, "alice");
        assertThat(access.canReceive(identity)).isFalse();
    }

    @Test
    void anInvalidatedSessionRevokesDelivery() {
        when(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .thenThrow(new IllegalStateException("Session invalidated"));
        assertThat(access.canReceive(identity)).isFalse();
    }

    @Test
    void anExpiredSessionIsRejectedWithoutLookingUpMembership() {
        when(session.getMaxInactiveInterval()).thenReturn(1);
        when(session.getLastAccessedTime()).thenReturn(System.currentTimeMillis() - 5000);
        assertThat(access.canReceive(identity)).isFalse();
        verifyNoInteractions(memberships);
    }

    @Test
    void aDifferentSignedInIdentityCannotReuseTheOriginalSocket() {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("bob", "unused", List.of()));
        when(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .thenReturn(context);
        assertThat(access.canReceive(identity)).isFalse();
        verifyNoInteractions(memberships);
    }

    @Test
    void aMissingSecurityContextRevokesDelivery() {
        when(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).thenReturn(null);
        assertThat(access.canReceive(identity)).isFalse();
        verifyNoInteractions(memberships);
    }

    @Test
    void anonymousAuthenticationDoesNotGrantDelivery() {
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new AnonymousAuthenticationToken("key", "alice",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        when(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY))
                .thenReturn(context);
        assertThat(access.canReceive(identity)).isFalse();
        verifyNoInteractions(memberships);
    }

    @Test
    void aNonexpiringSessionStillRequiresMembership() {
        when(session.getMaxInactiveInterval()).thenReturn(0);
        assertThat(access.canReceive(identity)).isTrue();
        verify(memberships).requireMember(7L, "alice");
    }
}
