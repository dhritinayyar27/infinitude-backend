package com.infinitude.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * MongoDB document for the {@code otp_verifications} collection (§5.1.1 PROJECT_ARCHITECTURE.md).
 *
 * <p><b>Design decision - how signup "pending user" state is handled:</b> per §17.2 the
 * {@code VerifySignupOtpRequest} DTO carries {@code name}, {@code email} AND {@code otp}
 * together. This lets {@code AuthService} create the {@link User} only once signup OTP
 * verification actually succeeds, using the {@code name} resent by the client at verify
 * time - there is no need to persist a "pendingName" on this short-lived record, and the
 * shape of this collection stays exactly as documented in §5.1.1 (no extra fields). This
 * keeps {@code otp_verifications} a pure, purpose-scoped OTP ledger, never a source of
 * user identity truth (that remains {@code users}, per §5.1.1's own note).</p>
 *
 * <p>Plaintext OTP is NEVER stored - only {@link #otpHash} (BCrypt) is persisted.</p>
 */
@Document(collection = "otp_verifications")
public class OtpVerification {

    @Id
    private String id;

    @Indexed
    private String email;

    private String otpHash;

    private OtpPurpose purpose;

    private Instant expiresAt;

    private int attemptCount;

    private int resendCount;

    private Instant createdAt;

    private boolean used;

    /** Timestamp of the most recent OTP (re)send - backs the resend cooldown (§17.4). */
    private Instant lastSentAt;

    public OtpVerification() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getOtpHash() {
        return otpHash;
    }

    public void setOtpHash(String otpHash) {
        this.otpHash = otpHash;
    }

    public OtpPurpose getPurpose() {
        return purpose;
    }

    public void setPurpose(OtpPurpose purpose) {
        this.purpose = purpose;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public int getResendCount() {
        return resendCount;
    }

    public void setResendCount(int resendCount) {
        this.resendCount = resendCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isUsed() {
        return used;
    }

    public void setUsed(boolean used) {
        this.used = used;
    }

    public Instant getLastSentAt() {
        return lastSentAt;
    }

    public void setLastSentAt(Instant lastSentAt) {
        this.lastSentAt = lastSentAt;
    }
}
