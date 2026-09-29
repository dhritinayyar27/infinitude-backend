package com.infinitude.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Reads the JWT from the httpOnly auth cookie (never an {@code Authorization} header, per the
 * binding token decision, §17.6 PROJECT_ARCHITECTURE.md) and, if valid, populates the
 * {@link SecurityContext} with the authenticated user's id/email so downstream code (e.g.
 * {@code GET /api/auth/me}, and future note ownership checks) can rely on
 * {@code SecurityContextHolder.getContext().getAuthentication()}.
 *
 * <p>An absent/invalid/expired token simply leaves the context unauthenticated - it does not
 * throw. Whether that's acceptable for the requested route is decided by {@link SecurityConfig}'s
 * authorization rules, not by this filter.</p>
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final TokenService tokenService;

    public JwtAuthenticationFilter(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        extractTokenFromCookies(request)
                .flatMap(tokenService::parseAndValidate)
                .ifPresent(claims -> authenticate(claims));

        filterChain.doFilter(request, response);
    }

    private void authenticate(Claims claims) {
        String userId = tokenService.extractUserId(claims);
        String email = tokenService.extractEmail(claims);
        AuthenticatedUser principal = new AuthenticatedUser(userId, email);

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private Optional<String> extractTokenFromCookies(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (AuthCookie.COOKIE_NAME.equals(cookie.getName())) {
                return Optional.ofNullable(cookie.getValue());
            }
        }
        return Optional.empty();
    }
}
