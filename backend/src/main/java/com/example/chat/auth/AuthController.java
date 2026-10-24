package com.example.chat.auth;

import java.security.Principal;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
// Login and logout are handled by SecurityConfig's filters, so they have no methods here.
public class AuthController {

    private final RegistrationService registrationService;
    private final CurrentUserService currentUserService;

    public AuthController(RegistrationService registrationService, CurrentUserService currentUserService) {
        this.registrationService = registrationService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(registrationService.register(request));
    }

    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        // The browser asks for this token, then sends it in a header when changing data.
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @GetMapping("/me")
    public UserResponse me(Principal principal) {
        // Principal is the account Spring Security has already identified from the session.
        return currentUserService.getByUsername(principal.getName());
    }
}
