package com.infinitude.security;

import com.infinitude.exception.RateLimitExceededException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Simple in-memory sliding-window rate limiter guarding the send-otp endpoints, in addition to
 * {@code OtpService}'s own per-record resend cooldown (§17.4/§11 item 11).
 *
 * <p><b>Design choice:</b> a lightweight in-memory limiter (a per-key {@link ConcurrentLinkedDeque}
 * of request timestamps, pruned on each check) is used instead of a library like Bucket4j. This
 * is intentionally simple and sufficient for a single-instance deployment; it is NOT shared
 * across multiple backend instances/nodes. If Infinitude is ever horizontally scaled, this
 * should be swapped for a shared-store limiter (e.g. Bucket4j backed by Redis) - the interface
 * here (`checkAllowedOrThrow(key)`) is deliberately narrow so that swap wouldn't require
 * touching call sites in {@code AuthService}.</p>
 */
@Component
public class RateLimiter {

    private final int maxRequests;
    private final Duration window;

    private final ConcurrentHashMap<String, Deque<Instant>> requestLog = new ConcurrentHashMap<>();

    public RateLimiter(@Value("${infinitude.auth.rate-limit.max-requests:5}") int maxRequests,
                        @Value("${infinitude.auth.rate-limit.window-seconds:900}") long windowSeconds) {
        this.maxRequests = maxRequests;
        this.window = Duration.ofSeconds(windowSeconds);
    }

    /**
     * Throws {@link RateLimitExceededException} if {@code key} (e.g. a normalized email) has
     * already made {@code maxRequests} requests within the configured window; otherwise records
     * this request and allows it through.
     */
    public void checkAllowedOrThrow(String key) {
        Instant now = Instant.now();
        Deque<Instant> timestamps = requestLog.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());

        synchronized (timestamps) {
            while (!timestamps.isEmpty() && Duration.between(timestamps.peekFirst(), now).compareTo(window) > 0) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= maxRequests) {
                throw new RateLimitExceededException();
            }
            timestamps.addLast(now);
        }
    }
}
