package com.infinitude.ai.prompt;

import org.springframework.stereotype.Component;

@Component
public class TocPromptBuilder {

    /**
     * Builds a prompt instructing Gemini to return a JSON table of contents.
     *
     * @param topic      the note topic
     * @param difficulty BEGINNER, INTERMEDIATE, or ADVANCED
     * @return the complete prompt string
     */
    public String build(String topic, String difficulty) {
        int minSections;
        int maxSections;
        switch (difficulty.toUpperCase()) {
            case "BEGINNER" -> { minSections = 6; maxSections = 12; }
            case "ADVANCED" -> { minSections = 10; maxSections = 18; }
            default -> { minSections = 8; maxSections = 15; } // INTERMEDIATE
        }

        return """
                You are an expert curriculum designer. Generate a comprehensive table of contents for a \
                %s-level guide on the topic: "%s".

                Requirements:
                - Generate between %d and %d top-level sections
                - Each section title must be a clear, descriptive heading
                - Subsections are optional but encouraged for complex topics
                - The sections should flow logically from foundational concepts to advanced ones
                - Tailor the depth and terminology to the %s difficulty level

                Return ONLY valid JSON in this exact structure, with no additional text, no markdown fences, \
                and no explanation:
                {
                  "topic": "<topic name>",
                  "sections": [
                    {
                      "title": "<section title>",
                      "subsections": ["<subsection 1>", "<subsection 2>"]
                    }
                  ]
                }

                Topic: %s
                Difficulty: %s
                """.formatted(difficulty, topic, minSections, maxSections, difficulty, topic, difficulty);
    }
}
