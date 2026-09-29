package com.infinitude.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * Writes the same error shape as {@link com.infinitude.exception.ErrorResponse}
 * (§10 PROJECT_ARCHITECTURE.md) for unauthenticated requests to protected routes, instead of
 * Spring Security's default plain-text/HTML 401 page.
 *
 * <p>Builds the JSON body manually (rather than autowiring an {@code ObjectMapper}) since this
 * runs at the security-filter layer, ahead of/independent from Spring MVC's message-converter
 * machinery, and to stay agnostic of which Jackson major version the Spring Boot version in use
 * wires up as the JSON bean. The shape is small and fixed, so hand-built JSON is simplest and
 * has no extra dependency risk.</p>
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                          AuthenticationException authException) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String path = escape(request.getRequestURI());
        String json = "{"
                + "\"timestamp\":\"" + LocalDateTime.now() + "\","
                + "\"status\":401,"
                + "\"error\":\"UNAUTHENTICATED\","
                + "\"message\":\"Authentication is required.\","
                + "\"path\":\"" + path + "\""
                + "}";
        response.getWriter().write(json);
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
