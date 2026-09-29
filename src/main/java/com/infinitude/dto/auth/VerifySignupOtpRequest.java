package com.infinitude.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/auth/signup/verify-otp} (§17.2/§17.3 PROJECT_ARCHITECTURE.md).
 *
 * <p>Carries {@code name} again (in addition to {@code email}/{@code otp}) so the
 * {@link com.infinitude.model.User} can be created only after OTP verification succeeds,
 * without needing to persist a "pending name" on the short-lived
 * {@link com.infinitude.model.OtpVerification} record - see the design-decision comment on
 * that class.</p>
 */
public class VerifySignupOtpRequest {

    @NotBlank(message = "Name is required")
    @Size(min = 1, max = 100, message = "Name must be between 1 and 100 characters")
    private String name;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid email address")
    private String email;

    @NotBlank(message = "OTP is required")
    @Pattern(regexp = "\\d{6}", message = "OTP must be a 6-digit numeric code")
    private String otp;

    public VerifySignupOtpRequest() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getOtp() {
        return otp;
    }

    public void setOtp(String otp) {
        this.otp = otp;
    }
}
