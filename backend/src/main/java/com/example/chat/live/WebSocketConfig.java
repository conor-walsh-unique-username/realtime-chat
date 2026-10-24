package com.example.chat.live;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final RoomSocketHandler handler;
    private final RoomSocketHandshakeInterceptor handshakeInterceptor;

    public WebSocketConfig(RoomSocketHandler handler, RoomSocketHandshakeInterceptor handshakeInterceptor) {
        this.handler = handler;
        this.handshakeInterceptor = handshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/rooms/*")
                .addInterceptors(handshakeInterceptor)
                // Browser WebSockets send cookies automatically; restrict which sites can open one.
                .setAllowedOrigins("http://localhost:3000", "http://localhost:8080");
    }
}