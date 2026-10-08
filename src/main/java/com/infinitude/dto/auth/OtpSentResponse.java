package com.infinitude.dto.auth;

public record OtpSentResponse(String message, long resendCooldownSeconds, long expiresInSeconds) {
}
