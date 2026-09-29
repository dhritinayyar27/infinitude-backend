package com.infinitude.security;

import com.infinitude.exception.InvalidOtpException;
import com.infinitude.exception.OtpCooldownException;
import com.infinitude.exception.OtpExpiredException;
import com.infinitude.exception.OtpLockedException;
import com.infinitude.exception.OtpNotFoundException;
import com.infinitude.model.OtpPurpose;
import com.infinitude.model.OtpVerification;
import com.infinitude.repository.OtpVerificationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * OTP generation/hashing/verification, backed by the {@code otp_verifications} collection
 * (§5.1.1, §17.4 PROJECT_ARCHITECTURE.md). Contains no HTTP/controller concerns.
 *
 * <p>Hashing uses BCrypt via Spring Security's {@link PasswordEncoder} - it's purpose-built
 * for one-way, salted, slow hashing of short secrets, which is exactly what a 6-digit OTP is.
 * Plaintext OTP is never persisted, only {@link OtpVerification#getOtpHash()}.</p>
 */
@Service
public class OtpService {

    private static final int OTP_LENGTH = 6;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final OtpVerificationRepository otpVerificationRepository;
    private final PasswordEncoder passwordEncoder;

    private final long otpExpirationSeconds;
    private final int otpMaxAttempts;
    private final long otpResendCooldownSeconds;

    public OtpService(OtpVerificationRepository otpVerificationRepository,
                       PasswordEncoder passwordEncoder,
                       @Value("${OTP_EXPIRATION_SECONDS:300}") long otpExpirationSeconds,
                       @Value("${OTP_MAX_ATTEMPTS:5}") int otpMaxAttempts,
                       @Value("${OTP_RESEND_COOLDOWN_SECONDS:30}") long otpResendCooldownSeconds) {
        this.otpVerificationRepository = otpVerificationRepository;
        this.passwordEncoder = passwordEncoder;
        this.otpExpirationSeconds = otpExpirationSeconds;
        this.otpMaxAttempts = otpMaxAttempts;
        this.otpResendCooldownSeconds = otpResendCooldownSeconds;
    }

    /**
     * Generates a fresh OTP for {@code email}/{@code purpose}, enforcing the resend cooldown
     * against the most recent record. Returns the PLAINTEXT otp so the caller (AuthService) can
     * hand it to {@code EmailService} immediately - it is never returned again nor persisted.
     */
    public String generateAndPersistOtp(String email, OtpPurpose purpose) {
        Optional<OtpVerification> latest =
                otpVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(email, purpose);

        Instant now = Instant.now();
        int resendCount = 0;
        if (latest.isPresent()) {
            OtpVerification prev = latest.get();
            if (prev.getLastSentAt() != null) {
                Duration sinceLastSend = Duration.between(prev.getLastSentAt(), now);
                long remaining = otpResendCooldownSeconds - sinceLastSend.getSeconds();
                if (remaining > 0) {
                    throw new OtpCooldownException(remaining);
                }
            }
            resendCount = prev.getResendCount() + 1;
        }

        String plainOtp = generateNumericOtp();

        OtpVerification record = new OtpVerification();
        record.setEmail(email);
        record.setPurpose(purpose);
        record.setOtpHash(passwordEncoder.encode(plainOtp));
        record.setExpiresAt(now.plusSeconds(otpExpirationSeconds));
        record.setAttemptCount(0);
        record.setResendCount(resendCount);
        record.setCreatedAt(now);
        record.setUsed(false);
        record.setLastSentAt(now);
        otpVerificationRepository.save(record);

        return plainOtp;
    }

    /**
     * Best-effort timing-side-channel mitigation for the §17.5 user-enumeration protection:
     * performs work comparable in cost to {@link #generateAndPersistOtp} (a repository lookup
     * plus a BCrypt hash) without persisting anything or producing a usable OTP, so the
     * "email doesn't exist"/"email already registered" branch in {@code AuthService} doesn't
     * complete measurably faster than the branch that actually generates and sends an OTP.
     */
    public void simulateOtpGenerationCost(String email, OtpPurpose purpose) {
        otpVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(email, purpose);
        passwordEncoder.encode(generateNumericOtp());
    }

    /**
     * Verifies {@code candidateOtp} against the most recent record for {@code email}/{@code purpose}.
     * On success, marks the record {@code used=true} so it cannot be replayed. Throws a specific
     * {@link com.infinitude.exception.OtpException} subtype for every rejection reason, none of
     * which leak internal detail beyond a safe, generic user-facing message (§17.4).
     */
    public void verifyOtp(String email, OtpPurpose purpose, String candidateOtp) {
        OtpVerification record = otpVerificationRepository
                .findTopByEmailAndPurposeOrderByCreatedAtDesc(email, purpose)
                .orElseThrow(OtpNotFoundException::new);

        if (record.isUsed()) {
            throw new OtpNotFoundException();
        }
        if (record.getAttemptCount() >= otpMaxAttempts) {
            throw new OtpLockedException();
        }
        if (Instant.now().isAfter(record.getExpiresAt())) {
            throw new OtpExpiredException();
        }

        boolean matches = passwordEncoder.matches(candidateOtp, record.getOtpHash());
        if (!matches) {
            record.setAttemptCount(record.getAttemptCount() + 1);
            otpVerificationRepository.save(record);
            if (record.getAttemptCount() >= otpMaxAttempts) {
                throw new OtpLockedException();
            }
            throw new InvalidOtpException();
        }

        record.setUsed(true);
        otpVerificationRepository.save(record);
    }

    private String generateNumericOtp() {
        int bound = (int) Math.pow(10, OTP_LENGTH);
        int value = SECURE_RANDOM.nextInt(bound);
        return String.format("%0" + OTP_LENGTH + "d", value);
    }
}
