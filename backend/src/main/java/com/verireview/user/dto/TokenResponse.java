package com.verireview.user.dto;

/** Token pair handed out on register/login/refresh. No entities leak here. */
public record TokenResponse(
    String accessToken, String refreshToken, String tokenType, long expiresIn) {
}
