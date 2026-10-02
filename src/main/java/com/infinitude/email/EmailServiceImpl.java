package com.infinitude.email;

import com.infinitude.model.OtpPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Concrete {@link EmailService} using {@link JavaMailSender} (spring-boot-starter-mail), with
 * SMTP config sourced entirely from environment variables (§11, §17.4/§17.8 - never hardcoded,
 * never logged).
 *
 * <p>SMTP configuration is required in every environment. OTPs are delivered only by email
 * and are never written to logs, including when delivery fails.</p>
 *
 * <p>Real send failures (e.g. bad credentials, SMTP outage) are caught and logged here rather
 * than propagated to the controller: the OTP record has already been persisted regardless of
 * delivery outcome, and propagating a delivery-specific error up would risk undermining the
 * user-enumeration protection (§17.5) by making failures observably different from successes.
 * Delivery health must be monitored via logs/alerting, not via the API response.</p>
 */
@Service
public class EmailServiceImpl implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailServiceImpl.class);

    private final JavaMailSender mailSender;
    private final String mailFrom;
    public EmailServiceImpl(ObjectProvider<JavaMailSender> mailSenderProvider,
                             @Value("${spring.mail.host:}") String mailHost,
                             @Value("${MAIL_FROM:}") String mailFrom) {
        JavaMailSender candidate = mailSenderProvider.getIfAvailable();
        if (candidate == null || mailHost == null || mailHost.isBlank()
                || mailFrom == null || mailFrom.isBlank()) {
            throw new IllegalStateException("OTP email requires SMTP configuration: set MAIL_HOST and MAIL_FROM, "
                    + "and configure MAIL_PORT, MAIL_USERNAME and MAIL_PASSWORD for your mail server.");
        }
        this.mailSender = candidate;
        this.mailFrom = mailFrom;
    }

    @Override
    public void sendOtpEmail(String toEmail, String otp, OtpPurpose purpose) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(mailFrom);
            message.setTo(toEmail);
            message.setSubject(subjectFor(purpose));
            message.setText(bodyFor(otp, purpose));
            mailSender.send(message);
            log.info("OTP email dispatched for purpose={} (recipient omitted from logs)", purpose);
        } catch (MailException ex) {
            log.error("Failed to send OTP email for purpose={} (error type={})", purpose,
                    ex.getClass().getSimpleName());
        }
    }

    private String subjectFor(OtpPurpose purpose) {
        return switch (purpose) {
            case SIGNUP -> "Your Infinitude signup verification code";
            case LOGIN -> "Your Infinitude login verification code";
        };
    }

    private String bodyFor(String otp, OtpPurpose purpose) {
        String action = purpose == OtpPurpose.SIGNUP ? "complete your signup" : "log in";
        return "Use the code below to " + action + " to Infinitude:\n\n" + otp
                + "\n\nThis code expires shortly and can only be used once. "
                + "If you did not request this, you can safely ignore this email.";
    }
}
