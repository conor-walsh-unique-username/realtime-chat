package com.example.chat.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// A quick liveness check. Actuator's separate health endpoint also checks the database.
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public HealthResponse health() {
        return new HealthResponse("UP", "chat-backend");
    }
}
