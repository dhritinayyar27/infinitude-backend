package com.infinitude.email;

import org.springframework.mail.MailSendException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

final class VercelEmailRelay {
    private static final Logger log = LoggerFactory.getLogger(VercelEmailRelay.class);
    private final URI endpoint;
    private final byte[] secret;
    private final HttpClient client;

    VercelEmailRelay(String url, String secret) {
        this(url, secret, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    VercelEmailRelay(String url, String secret, HttpClient client) {
        try {
            this.endpoint = URI.create(url);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("EMAIL_RELAY_URL must be a valid HTTPS URL.");
        }
        if (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getFragment() != null) {
            throw new IllegalStateException("EMAIL_RELAY_URL must be a valid HTTPS URL.");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        if (this.secret.length < 32) {
            throw new IllegalStateException("EMAIL_RELAY_SECRET must contain at least 32 bytes.");
        }
        this.client = client;
    }

    void send(String recipient, byte[] mime) {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String requestId = UUID.randomUUID().toString();
        String signature;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((timestamp + "\n" + requestId + "\n" + recipient + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            signature = HexFormat.of().formatHex(mac.doFinal(mime));
        } catch (GeneralSecurityException ex) {
            throw new MailSendException("Unable to sign OTP delivery request.");
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(25))
                .header("Content-Type", "application/octet-stream")
                .header("X-OTP-Timestamp", timestamp)
                .header("X-OTP-Request-Id", requestId)
                .header("X-OTP-Recipient", recipient)
                .header("X-OTP-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(mime))
                .build();
        try {
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 204) {
                log.error("OTP relay rejected delivery (HTTP status={}).", response.statusCode());
                throw new MailSendException("OTP relay returned HTTP " + response.statusCode() + ".");
            }
        } catch (IOException ex) {
            log.error("OTP relay connection failed (error type={}).", ex.getClass().getSimpleName());
            throw new MailSendException("OTP relay connection failed.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MailSendException("OTP relay request interrupted.");
        }
    }
}
