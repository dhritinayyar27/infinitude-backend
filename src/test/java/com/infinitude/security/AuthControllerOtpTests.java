package com.infinitude.security;

import com.infinitude.dto.auth.SendOtpRequest;
import com.infinitude.dto.auth.SignupRequest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthControllerOtpTests {
    private final AuthService authService = mock(AuthService.class);
    private final RateLimiter limiter = mock(RateLimiter.class);
    private final AuthController controller = new AuthController(authService, limiter, 45, 90);

    @Test
    void sendResponsesExposeOnlyGenericMessageAndPublicTimingConfiguration() throws Exception {
        var login = controller.loginSendOtp(new SendOtpRequest("recipient@example.test"));
        var request = new SignupRequest();
        request.setName("Test User");
        request.setEmail("recipient@example.test");
        var signup = controller.signupSendOtp(request);
        assertEquals(login.getBody(), signup.getBody());
        assertEquals(200, login.getStatusCode().value());
        assertNotNull(login.getBody());
        assertEquals("If the email is valid, an OTP has been sent.", login.getBody().message());
        assertEquals(45, login.getBody().resendCooldownSeconds());
        assertEquals(90, login.getBody().expiresInSeconds());
        var json = new ObjectMapper().valueToTree(login.getBody());
        assertEquals(3, json.size());
        assertFalse(json.has("otp"));
        assertFalse(json.has("email"));
        verify(limiter).checkAllowedOrThrow("login:recipient@example.test");
        verify(limiter).checkAllowedOrThrow("signup:recipient@example.test");
        verify(authService).sendLoginOtp("recipient@example.test");
        verify(authService).sendSignupOtp("Test User", "recipient@example.test");
    }
}
