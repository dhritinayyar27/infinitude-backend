package com.infinitude.security;

import com.infinitude.dto.auth.AuthResponse;
import com.infinitude.dto.auth.UserResponse;
import com.infinitude.email.EmailService;
import com.infinitude.exception.OtpCooldownException;
import com.infinitude.exception.UserAlreadyExistsException;
import com.infinitude.model.OtpPurpose;
import com.infinitude.model.User;
import com.infinitude.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Orchestrates the Email + OTP login/signup business logic (§17.1/§17.2 PROJECT_ARCHITECTURE.md).
 * Calls {@link OtpService}, {@link TokenService}, {@link EmailService} and {@link UserRepository}.
 * Never returns the {@link User} entity directly - always maps to {@link UserResponse}/
 * {@link AuthResponse}.
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final OtpService otpService;
    private final EmailService emailService;
    private final TokenService tokenService;

    public AuthService(UserRepository userRepository,
                        OtpService otpService,
                        EmailService emailService,
                        TokenService tokenService) {
        this.userRepository = userRepository;
        this.otpService = otpService;
        this.emailService = emailService;
        this.tokenService = tokenService;
    }

    /**
     * §17.1/§17.5: only sends an OTP if the email belongs to an existing user, but this method
     * itself does not signal that distinction to the caller - {@code AuthController} always
     * returns the same generic message regardless of the outcome here.
     */
    public void sendLoginOtp(String rawEmail) {
        String email = normalize(rawEmail);
        Optional<User> user = userRepository.findByEmail(email);
        if (user.isEmpty()) {
            // Enumeration protection (§17.5): do comparable work (DB lookup + BCrypt hash) so
            // response timing doesn't obviously differ from the "user exists" path, but do NOT
            // generate/persist/send a real OTP for an email with no account.
            otpService.simulateOtpGenerationCost(email, OtpPurpose.LOGIN);
            return;
        }
        try {
            String otp = otpService.generateAndPersistOtp(email, OtpPurpose.LOGIN);
            dispatchOtpEmailAsync(email, otp, OtpPurpose.LOGIN);
        } catch (OtpCooldownException ex) {
            // §17.5: a resend-cooldown hit must never produce an observably different response
            // (status/body) than the "email doesn't exist" branch above - swallow it here so
            // AuthController always returns the same generic 200 message either way. The
            // cooldown has already done its job (no duplicate OTP/email was generated/sent).
        }
    }

    public AuthResponse verifyLoginOtp(String rawEmail, String otp) {
        String email = normalize(rawEmail);
        otpService.verifyOtp(email, OtpPurpose.LOGIN, otp);

        User user = userRepository.findByEmail(email)
                // The OTP record could only have been created for an existing user (see
                // sendLoginOtp above), so this should be unreachable in practice; treated as an
                // auth failure rather than leaking internal state if it ever happens.
                .orElseThrow(() -> new org.springframework.security.authentication.BadCredentialsException(
                        "Account no longer exists."));

        user.setLastLoginAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        userRepository.save(user);

        return new AuthResponse(toUserResponse(user));
    }

    /**
     * §17.1/§17.5: only actually sends a signup OTP for emails that are NOT already registered;
     * the caller always returns the same generic message regardless of the outcome here.
     */
    public void sendSignupOtp(String rawName, String rawEmail) {
        String email = normalize(rawEmail);
        if (userRepository.existsByEmail(email)) {
            otpService.simulateOtpGenerationCost(email, OtpPurpose.SIGNUP);
            return;
        }
        try {
            String otp = otpService.generateAndPersistOtp(email, OtpPurpose.SIGNUP);
            dispatchOtpEmailAsync(email, otp, OtpPurpose.SIGNUP);
        } catch (OtpCooldownException ex) {
            // See sendLoginOtp - same enumeration-protection rationale, mirrored for signup.
        }
    }

    /**
     * Creates the {@link User} only now, after OTP verification has actually succeeded (see the
     * design-decision comment on {@code OtpVerification}/{@code VerifySignupOtpRequest}).
     */
    public AuthResponse verifySignupOtp(String rawName, String rawEmail, String otp) {
        String email = normalize(rawEmail);
        otpService.verifyOtp(email, OtpPurpose.SIGNUP, otp);

        if (userRepository.existsByEmail(email)) {
            throw new UserAlreadyExistsException();
        }

        User user = new User(rawName.trim(), email);
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);

        return new AuthResponse(toUserResponse(user));
    }

    public String issueSessionToken(UserResponse user) {
        return tokenService.generateToken(user.getId(), user.getEmail());
    }

    public long tokenLifetimeSeconds() {
        return tokenService.getExpirationSeconds();
    }

    public UserResponse getCurrentUser(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new org.springframework.security.authentication.BadCredentialsException(
                        "Account no longer exists."));
        return toUserResponse(user);
    }

    private UserResponse toUserResponse(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail());
    }

    /**
     * Fires the OTP email off of the request thread so the synchronous send-otp response time
     * doesn't include SMTP/network round-trip latency - a real send could otherwise take far
     * longer than the no-op branch above, reopening the timing side-channel §17.5 requires be
     * kept "as consistent as practical". {@code EmailServiceImpl} already catches and logs its
     * own delivery failures, so nothing here needs to observe the outcome.
     */
    private void dispatchOtpEmailAsync(String email, String otp, OtpPurpose purpose) {
        CompletableFuture.runAsync(() -> emailService.sendOtpEmail(email, otp, purpose));
    }

    private String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
