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
 * <p><b>Dev fallback (must not fail startup without SMTP configured):</b> Spring Boot only
 * auto-configures a {@link JavaMailSender} bean when {@code spring.mail.host} is set, so
 * {@code mailSender} is injected as optional ({@code required = false}). When it's absent:</p>
 * <ul>
 *   <li>If {@code infinitude.email.dev-mode=true} (default, intended for local/dev only): the
 *       OTP is logged at INFO level behind an explicit "[DEV MODE]" marker so a developer can
 *       complete the OTP flow without real SMTP. This path is the ONLY place in the codebase
 *       a raw OTP may ever be written to a log, and only because there is no other delivery
 *       channel in that mode.</li>
 *   <li>If {@code infinitude.email.dev-mode=false} (required for any real deployment): the OTP
 *       value is NEVER logged, regardless of whether SMTP is configured. If SMTP is also not
 *       configured in that case, delivery is impossible and this is logged as a configuration
 *       error (no OTP value included) - operators must set {@code MAIL_HOST}/etc.</li>
 * </ul>
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
    private final boolean devMode;

    public EmailServiceImpl(ObjectProvider<JavaMailSender> mailSenderProvider,
                             @Value("${MAIL_FROM:no-reply@infinitude.local}") String mailFrom,
                             @Value("${infinitude.email.dev-mode:true}") boolean devMode) {
        // ObjectProvider tolerates zero JavaMailSender beans (SMTP not configured) instead of
        // throwing NoSuchBeanDefinitionException at startup - required for the dev fallback.
        this.mailSender = mailSenderProvider.getIfAvailable();
        this.mailFrom = mailFrom;
        this.devMode = devMode;
    }

    @Override
    public void sendOtpEmail(String toEmail, String otp, OtpPurpose purpose) {
        if (mailSender != null) {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(mailFrom);
                message.setTo(toEmail);
                message.setSubject(subjectFor(purpose));
                message.setText(bodyFor(otp, purpose));
                mailSender.send(message);
                log.info("OTP email dispatched for purpose={} (recipient omitted from logs)", purpose);
            } catch (MailException ex) {
                // Never include the OTP value or full stack trace details in logs beyond what's
                // needed to diagnose delivery config issues.
                log.error("Failed to send OTP email for purpose={}: {}", purpose, ex.getMessage());
            }
            return;
        }

        // No SMTP configured - dev-only fallback.
        if (devMode) {
            log.info("[DEV MODE] SMTP not configured - infinitude.email.dev-mode=true so the OTP is being "
                    + "logged instead of emailed. This MUST be false with real SMTP configured in production. "
                    + "purpose={} otp={}", purpose, otp);
        } else {
            log.error("Cannot deliver OTP email: no SMTP configured (MAIL_HOST/MAIL_PORT/MAIL_USERNAME/"
                    + "MAIL_PASSWORD) and infinitude.email.dev-mode=false. Configure SMTP for this environment.");
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
