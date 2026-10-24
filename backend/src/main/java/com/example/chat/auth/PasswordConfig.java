package com.example.chat.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

import java.util.Map;

@Configuration
public class PasswordConfig {

    // @Bean lets Spring pass this same password encoder into registration and login.
    @Bean
    public PasswordEncoder passwordEncoder() {
        // Each password gets a random 16-byte salt; 600,000 rounds slow down guessing.
        var pbkdf2 = new Pbkdf2PasswordEncoder(
                "", 16, 600_000,
                Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256
        );
        // The stored {pbkdf2-v1} prefix records which encoder can check this hash later.
        return new DelegatingPasswordEncoder(
                "pbkdf2-v1", Map.of("pbkdf2-v1", pbkdf2)
        );
    }
}
