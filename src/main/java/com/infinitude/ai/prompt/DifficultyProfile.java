package com.infinitude.ai.prompt;

import java.util.Locale;

public enum DifficultyProfile {
    BEGINNER(6, 12, 120, "150-250",
            "Assume no prior knowledge. Use plain language, define every new term, focus on foundations "
                    + "and build understanding step by step with simple practical examples. "
                    + "Do not introduce specialist or advanced material without prerequisites."),
    INTERMEDIATE(8, 15, 180, "250-400",
            "Assume familiarity with the fundamentals. Build on them with practical applications, "
                    + "relationships between concepts, common pitfalls and moderately complex worked examples. "
                    + "Explain why techniques work and when to use them without repeating basic introductions."),
    ADVANCED(10, 18, 260, "400-600",
            "Assume strong foundational knowledge. Focus on technical depth, internal mechanisms, "
                    + "trade-offs, limitations, edge cases and complex worked examples. Include rigorous reasoning "
                    + "and domain-appropriate formalism rather than a beginner-level overview.");

    private final int minSections;
    private final int maxSections;
    private final int minimumExplanationWords;
    private final String targetWords;
    private final String guidance;

    DifficultyProfile(int minSections, int maxSections, int minimumExplanationWords,
                      String targetWords, String guidance) {
        this.minSections = minSections;
        this.maxSections = maxSections;
        this.minimumExplanationWords = minimumExplanationWords;
        this.targetWords = targetWords;
        this.guidance = guidance;
    }

    public static DifficultyProfile from(String difficulty) {
        if (difficulty == null) throw new IllegalArgumentException("A difficulty level is required.");
        try {
            return valueOf(difficulty.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Difficulty must be BEGINNER, INTERMEDIATE, or ADVANCED.");
        }
    }

    public int minSections() { return minSections; }
    public int maxSections() { return maxSections; }
    public int minimumExplanationWords() { return minimumExplanationWords; }
    public String targetWords() { return targetWords; }
    public String guidance() { return guidance; }
}
