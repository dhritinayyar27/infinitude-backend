package com.infinitude.security;

/**
 * Principal placed into the {@link org.springframework.security.core.context.SecurityContext}
 * by {@link JwtAuthenticationFilter} after successful JWT validation. Deliberately minimal
 * (userId + email only) - enough for {@code GET /api/auth/me} and, later, for note/section
 * ownership checks (§10, §17.7) without a DB round-trip on every request.
 */
public class AuthenticatedUser {

    private final String userId;
    private final String email;

    public AuthenticatedUser(String userId, String email) {
        this.userId = userId;
        this.email = email;
    }

    public String getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }
}
