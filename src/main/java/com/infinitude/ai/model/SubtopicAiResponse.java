package com.infinitude.ai.model;

import java.util.List;

public record SubtopicAiResponse(String title, String explanation,
                                List<String> keyConcepts, List<String> examples) {}
