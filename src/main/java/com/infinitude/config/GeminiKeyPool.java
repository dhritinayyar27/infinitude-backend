package com.infinitude.config;

import java.util.Arrays;
import java.util.List;

/**
 * Immutable backend-only credentials. Never include this object or its keys in
 * API DTOs, logs, exception messages, or HTTP URLs.
 */
public final class GeminiKeyPool {
    private final List<String> keys;

    private GeminiKeyPool(List<String> keys) {
        this.keys = List.copyOf(keys);
    }

    /** Parses both the legacy single key and comma-separated key lists. */
    public static GeminiKeyPool parse(String commaSeparatedKeys) {
        if (commaSeparatedKeys == null) {
            return new GeminiKeyPool(List.of());
        }
        return new GeminiKeyPool(Arrays.stream(commaSeparatedKeys.split(","))
                .map(String::trim)
                .filter(key -> !key.isEmpty())
                .distinct()
                .toList());
    }

    /**
     * A nonempty normalized plural pool replaces (does not append to) the legacy
     * pool. Empty/blank plural configuration falls back to the legacy setting.
     */
    public static GeminiKeyPool resolve(String legacyKeys, String pluralKeys) {
        GeminiKeyPool plural = parse(pluralKeys);
        return plural.isEmpty() ? parse(legacyKeys) : plural;
    }

    /** Backend-only, immutable ordered keys for the AI adapter. */
    public List<String> keys() {
        return keys;
    }

    public boolean isEmpty() {
        return keys.isEmpty();
    }

    /** Transitional backend-only transport through the existing AiService signature. */
    public String commaSeparatedKeys() {
        return String.join(",", keys);
    }

    @Override
    public String toString() {
        return "GeminiKeyPool[REDACTED]";
    }
}
