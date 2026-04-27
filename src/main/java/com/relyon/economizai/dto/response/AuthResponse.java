package com.relyon.economizai.dto.response;

public record AuthResponse(
        String token,
        UserResponse user
) {}
