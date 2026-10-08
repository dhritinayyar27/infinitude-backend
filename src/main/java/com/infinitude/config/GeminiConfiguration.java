package com.infinitude.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Stream;

/** Immutable server-owned Gemini configuration shared by orchestration and AI adapters. */
@Component
public final class GeminiConfiguration {
    public static final String DEFAULT_MODEL = "gemini-3.5-flash-lite";
    public static final List<String> FALLBACK_MODELS = List.of(
            "gemini-3.8-flash",
            "gemini-3.6-flash",
            "gemini-3.5-flash-lite",
            "gemini-3.7-flash",
            "gemini-3.5-flash",
            "gemini-flash-latest",
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-3.1-flash-lite-preview",
            "gemini-flash-lite-latest");

    private final GeminiKeyPool keyPool;
    private final String preferredModel;

    public GeminiConfiguration(
            @Value("${infinitude.gemini.api-key:${GEMINI_API_KEY:}}") String legacyKeys,
            @Value("${infinitude.gemini.api-keys:${GEMINI_API_KEYS:}}") String pluralKeys,
            @Value("${infinitude.gemini.model:${GEMINI_MODEL:gemini-3.5-flash-lite}}") String preferredModel) {
        this.keyPool = GeminiKeyPool.resolve(legacyKeys, pluralKeys);
        this.preferredModel = normalizeModel(preferredModel);
    }

    public GeminiKeyPool keyPool() {
        return keyPool;
    }

    public String preferredModel() {
        return preferredModel;
    }

    /** Preferred model first, then the exact fallback order, without duplicate attempts. */
    public static List<String> modelCandidates(String preferredModel) {
        return Stream.concat(Stream.of(normalizeModel(preferredModel)), FALLBACK_MODELS.stream())
                .distinct()
                .toList();
    }

    private static String normalizeModel(String model) {
        return model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
    }

    @Override
    public String toString() {
        return "GeminiConfiguration[REDACTED]";
    }
}
