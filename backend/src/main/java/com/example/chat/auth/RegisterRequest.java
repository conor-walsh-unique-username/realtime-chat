package com.example.chat.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "Username is required.")
        @Pattern(regexp = "[A-Za-z0-9_]{3,30}", message = "Use 3–30 letters, digits, or underscores.")
        String username,

        @NotBlank(message = "Password is required.")
        @Size(min = 12, max = 128, message = "Use a password between 12 and 128 characters.")
        String password
) {
    // Records normally print every field. Keep the password out of accidental logging.
    @Override
    public String toString() {
        return "RegisterRequest[username=" + username + ", password=<redacted>]";
    }
}
