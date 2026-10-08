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
        DifficultyProfile profile = DifficultyProfile.from(difficulty);

        return """
                You are an expert curriculum designer. Generate a comprehensive table of contents for a \
                %s-level guide on the topic: "%s".

                Requirements:
                - Generate between %d and %d top-level sections
                - Each section title must be a clear, descriptive heading
                - Add specific subtopics wherever necessary to fully cover a section; do not repeat parent titles
                - Keep the total number of topics (parents plus subtopics) at or below 100
                - Titles must be non-blank, at most 300 characters, and contain no numbering
                - Arrange prerequisites before dependent topics and avoid duplicate or overlapping topics
                - Every listed topic will receive its own detailed notes, including parent topics
                - Keep all topics strictly related to the requested subject
                - Tailor scope, prerequisites and terminology to the selected level, not just the number of sections
                Selected %s level requirements:
                %s
                Maintain this level throughout the outline; do not escalate beyond it just to fill sections.

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
                """.formatted(profile.name(), topic, profile.minSections(), profile.maxSections(),
                profile.name(), profile.guidance(), topic, difficulty);
    }
}
