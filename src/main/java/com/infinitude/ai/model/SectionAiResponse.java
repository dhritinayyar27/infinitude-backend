package com.infinitude.ai.model;

import java.util.List;

public record SectionAiResponse(String explanation, List<String> keyConcepts,
                                List<String> examples, List<SubtopicAiResponse> subtopics) {
    public SectionAiResponse(String explanation, List<String> keyConcepts, List<String> examples) {
        this(explanation, keyConcepts, examples, List.of());
    }
}
