package com.infinitude.security;

import com.infinitude.dto.auth.AuthResponse;
import com.infinitude.dto.auth.MessageResponse;
import com.infinitude.dto.auth.SendOtpRequest;
import com.infinitude.dto.auth.SignupRequest;
import com.infinitude.dto.auth.UserResponse;
import com.infinitude.dto.auth.VerifyOtpRequest;
import com.infinitude.dto.auth.VerifySignupOtpRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP-only layer for Email + OTP auth (§17.3 PROJECT_ARCHITECTURE.md). All business logic
 * lives in {@link AuthService}/{@link OtpService} - this class only validates requests, calls
 * the service, and shapes the HTTP response (status/cookie/DTO).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String GENERIC_OTP_SENT_MESSAGE = "If the email is valid, an OTP has been sent.";

    private final AuthService authService;
    private final RateLimiter rateLimiter;

    public AuthController(AuthService authService, RateLimiter rateLimiter) {
        this.authService = authService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/login/send-otp")
    public ResponseEntity<MessageResponse> loginSendOtp(@Valid @RequestBody SendOtpRequest request) {
        rateLimiter.checkAllowedOrThrow("login:" + request.getEmail().toLowerCase());
        authService.sendLoginOtp(request.getEmail());
        // §17.5: identical response regardless of whether the email exists.
        return ResponseEntity.ok(new MessageResponse(GENERIC_OTP_SENT_MESSAGE));
    }

    @PostMapping("/login/verify-otp")
    public ResponseEntity<AuthResponse> loginVerifyOtp(@Valid @RequestBody VerifyOtpRequest request,
                                                        HttpServletResponse response) {
        AuthResponse authResponse = authService.verifyLoginOtp(request.getEmail(), request.getOtp());
        attachSessionCookie(response, authResponse.getUser());
        return ResponseEntity.ok(authResponse);
    }

    @PostMapping("/signup/send-otp")
    public ResponseEntity<MessageResponse> signupSendOtp(@Valid @RequestBody SignupRequest request) {
        rateLimiter.checkAllowedOrThrow("signup:" + request.getEmail().toLowerCase());
        authService.sendSignupOtp(request.getName(), request.getEmail());
        // §17.5: identical response regardless of whether the email is already registered.
        return ResponseEntity.ok(new MessageResponse(GENERIC_OTP_SENT_MESSAGE));
    }

    @PostMapping("/signup/verify-otp")
    public ResponseEntity<AuthResponse> signupVerifyOtp(@Valid @RequestBody VerifySignupOtpRequest request,
                                                         HttpServletResponse response) {
        AuthResponse authResponse = authService.verifySignupOtp(request.getName(), request.getEmail(), request.getOtp());
        attachSessionCookie(response, authResponse.getUser());
        return ResponseEntity.ok(authResponse);
    }

    @PostMapping("/logout")
    public ResponseEntity<MessageResponse> logout(HttpServletResponse response) {
        ResponseCookie clearCookie = ResponseCookie.from(AuthCookie.COOKIE_NAME, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, clearCookie.toString());
        return ResponseEntity.ok(new MessageResponse("Logged out."));
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> me() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser principal)) {
            // /api/auth/** is route-public, but /me itself always requires a valid cookie (§17.3).
            throw new BadCredentialsException("Not authenticated.");
        }
        UserResponse user = authService.getCurrentUser(principal.getUserId());
        return ResponseEntity.ok(user);
    }

    private void attachSessionCookie(HttpServletResponse response, UserResponse user) {
        String token = authService.issueSessionToken(user);
        ResponseCookie cookie = ResponseCookie.from(AuthCookie.COOKIE_NAME, token)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(java.time.Duration.ofSeconds(authTokenLifetimeSeconds()))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private long authTokenLifetimeSeconds() {
        return authService.tokenLifetimeSeconds();
    }
}
