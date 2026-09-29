package com.infinitude.repository;

import com.infinitude.model.OtpPurpose;
import com.infinitude.model.OtpVerification;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data MongoDB repository for {@link OtpVerification} (§10, §17.2 PROJECT_ARCHITECTURE.md).
 * No business logic here - {@code OtpService} owns all OTP business rules.
 */
public interface OtpVerificationRepository extends MongoRepository<OtpVerification, String> {

    /**
     * Most recent (by creation time) OTP record for a given email + purpose, used/unused,
     * so {@code OtpService} can decide whether a fresh record is needed or an active one
     * should be reused/superseded.
     */
    Optional<OtpVerification> findTopByEmailAndPurposeOrderByCreatedAtDesc(String email, OtpPurpose purpose);

    List<OtpVerification> findByEmailAndPurpose(String email, OtpPurpose purpose);
}
