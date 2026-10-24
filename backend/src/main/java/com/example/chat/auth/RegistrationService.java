package com.example.chat.auth;

import java.time.Instant;
import java.util.Locale;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.chat.user.ChatUser;
import com.example.chat.user.ChatUserRepository;

@Service
public class RegistrationService {

    private final ChatUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public RegistrationService(ChatUserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        // Store one spelling so Alice and ALICE cannot become separate accounts.
        String username = request.username().toLowerCase(Locale.ROOT);

        if (userRepository.existsByUsername(username)) {
            throw new UsernameTakenException();
        }

        // Only the hash reaches the database; login checks passwords against it later.
        String passwordHash = passwordEncoder.encode(request.password());

        try {
            // Flush sends the INSERT now, letting this catch block see a duplicate error.
            ChatUser saved = userRepository.saveAndFlush(
                    new ChatUser(username, passwordHash, Instant.now())
            );
            return UserResponse.from(saved);
        } catch (DataIntegrityViolationException exception) {
            // Another request can claim the username after our initial check.
            if (isUsernameConflict(exception)) {
                throw new UsernameTakenException();
            }
            throw exception;
        }
    }

    private boolean isUsernameConflict(Throwable exception) {
        // Only translate this specific constraint; other database errors need to surface.
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "uq_chat_users_username".equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
