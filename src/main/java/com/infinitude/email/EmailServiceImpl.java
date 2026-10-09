package com.infinitude.email;

import com.infinitude.model.OtpPurpose;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * OTP delivery via local SMTP or a signed, server-to-server Vercel relay.
 *
 * <p>Delivery configuration is required in every environment. OTPs are delivered only by email
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
    private final VercelEmailRelay relay;
    private final String mailFrom;
    private final long expirationSeconds;
    private final String template;
    private final ClassPathResource logo = new ClassPathResource("email/infinitude-logo.png");

    public EmailServiceImpl(ObjectProvider<JavaMailSender> mailSenderProvider,
                             String mailHost, String mailFrom, long expirationSeconds) {
        this(mailSenderProvider, mailHost, mailFrom, expirationSeconds, "smtp", "", "");
    }

    @Autowired
    public EmailServiceImpl(ObjectProvider<JavaMailSender> mailSenderProvider,
                             @Value("${spring.mail.host:}") String mailHost,
                             @Value("${MAIL_FROM:}") String mailFrom,
                             @Value("${OTP_EXPIRATION_SECONDS:300}") long expirationSeconds,
                             @Value("${infinitude.email.delivery:smtp}") String delivery,
                             @Value("${infinitude.email.relay-url:}") String relayUrl,
                             @Value("${infinitude.email.relay-secret:}") String relaySecret) {
        if (!"smtp".equals(delivery) && !"vercel".equals(delivery)) {
            throw new IllegalStateException("EMAIL_DELIVERY must be smtp or vercel.");
        }
        this.relay = "vercel".equals(delivery) ? new VercelEmailRelay(relayUrl, relaySecret) : null;
        JavaMailSender candidate = relay == null ? mailSenderProvider.getIfAvailable() : null;
        if (mailFrom == null || mailFrom.isBlank()) {
            throw new IllegalStateException("OTP email requires MAIL_FROM.");
        }
        if (relay == null && (candidate == null || mailHost == null || mailHost.isBlank())) {
            throw new IllegalStateException("OTP email requires SMTP configuration: set MAIL_HOST and MAIL_FROM, "
                    + "and configure MAIL_PORT, MAIL_USERNAME and MAIL_PASSWORD for your mail server.");
        }
        this.mailSender = candidate;
        this.mailFrom = mailFrom;
        if (expirationSeconds <= 0) {
            throw new IllegalStateException("OTP_EXPIRATION_SECONDS must be positive.");
        }
        this.expirationSeconds = expirationSeconds;
        try (var stream = new ClassPathResource("email/otp.html").getInputStream()) {
            this.template = StreamUtils.copyToString(stream, StandardCharsets.UTF_8);
            if (!logo.exists()) {
                throw new IllegalStateException("Infinitude email logo is missing.");
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to load the Infinitude OTP email template.", ex);
        }
    }

    @Override
    public void sendOtpEmail(String toEmail, String otp, OtpPurpose purpose) {
        try {
            var mimeMessage = relay == null ? mailSender.createMimeMessage()
                    : new MimeMessage(Session.getInstance(new Properties()));
            var message = new MimeMessageHelper(mimeMessage, true, StandardCharsets.UTF_8.name());
            message.setFrom(mailFrom);
            message.setTo(toEmail);
            message.setSubject(subjectFor(purpose));
            message.setText(bodyFor(otp, purpose), true);
            message.addInline("infinitude-logo", logo, "image/png");
            if (relay == null) {
                mailSender.send(mimeMessage);
            } else {
                mimeMessage.saveChanges();
                var content = new ByteArrayOutputStream();
                try {
                    mimeMessage.writeTo(content);
                } catch (IOException ex) {
                    throw new MailSendException("Unable to serialize OTP email.");
                }
                relay.send(toEmail, content.toByteArray());
            }
            log.info("OTP email dispatched for purpose={} (recipient omitted from logs)", purpose);
        } catch (MailException | MessagingException ex) {
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
        String action = purpose == OtpPurpose.SIGNUP ? "create your account" : "log in to your account";
        String validity = expirationSeconds % 60 == 0
                ? (expirationSeconds / 60) + " minute" + (expirationSeconds == 60 ? "" : "s")
                : expirationSeconds + " second" + (expirationSeconds == 1 ? "" : "s");
        return template.replace("{{action}}", action)
                .replace("{{validity}}", validity)
                .replace("{{otp}}", HtmlUtils.htmlEscape(otp));
    }
}
