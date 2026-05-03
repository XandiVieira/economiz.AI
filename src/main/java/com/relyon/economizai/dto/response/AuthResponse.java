package com.relyon.economizai.dto.response;

public record AuthResponse(
        String token,
        String refreshToken,
        UserResponse user
) {}
