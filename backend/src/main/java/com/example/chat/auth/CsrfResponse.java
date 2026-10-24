package com.example.chat.auth;

public record CsrfResponse(String headerName, String token) {
}