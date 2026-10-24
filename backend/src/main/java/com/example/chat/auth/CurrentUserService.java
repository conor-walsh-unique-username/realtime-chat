package com.example.chat.auth;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.example.chat.user.ChatUser;
import com.example.chat.user.ChatUserRepository;

@Service
public class CurrentUserService {

    private final ChatUserRepository userRepository;

    public CurrentUserService(ChatUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public UserResponse getByUsername(String username) {
        return UserResponse.from(requireAccount(username));
    }

    @Transactional(readOnly = true)
    public ChatUser requireAccount(String username) {
        // A session can outlive a deleted account, so check the row still exists.
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Please sign in again."
                ));
    }
}
