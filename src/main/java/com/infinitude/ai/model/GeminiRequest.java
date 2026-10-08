package com.infinitude.ai.model;

import java.util.List;

public class GeminiRequest {
    public List<GeminiContent> contents;
    public GeminiGenerationConfig generationConfig;
    public GeminiContent systemInstruction;
}
