package com.infinitude.security;

/**
 * Shared constants for the auth cookie so {@code AuthController} (writes it) and
 * {@code JwtAuthenticationFilter} (reads it) stay in sync (§17.6 PROJECT_ARCHITECTURE.md).
 */
public final class AuthCookie {

    public static final String COOKIE_NAME = "infinitude_token";

    private AuthCookie() {
    }
}
