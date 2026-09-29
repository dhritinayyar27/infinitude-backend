package com.infinitude.dto.auth;

/**
 * Response returned after a successful OTP verification (login or signup). Per the binding
 * token decision (§17.6 PROJECT_ARCHITECTURE.md) the JWT itself is NEVER included here - it is
 * only ever delivered via an {@code httpOnly} {@code Set-Cookie} header. This DTO carries user
 * info only.
 */
public class AuthResponse {

    private UserResponse user;

    public AuthResponse() {
    }

    public AuthResponse(UserResponse user) {
        this.user = user;
    }

    public UserResponse getUser() {
        return user;
    }

    public void setUser(UserResponse user) {
        this.user = user;
    }
}
