package com.infinitude.ai;

import com.infinitude.ai.model.SubtopicAiResponse;
import java.util.List;

public final class NotesTestContent {
    private NotesTestContent() {}

    public static List<SubtopicAiResponse> subtopics() {
        return List.of(
                new SubtopicAiResponse("Definitions", "Supporting explanation ".repeat(50),
                        List.of("Concept A", "Concept B", "Concept C"), List.of("Worked example")),
                new SubtopicAiResponse("Practical applications", "Supporting explanation ".repeat(50),
                        List.of("Concept A", "Concept B", "Concept C"), List.of("Worked example")));
    }
}
