package com.infinitude.ai.prompt;

import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class NotesGenerationGuide {
    private static final String SYSTEM_PROMPT = load();

    private NotesGenerationGuide() {}

    public static String systemPrompt() { return SYSTEM_PROMPT; }

    private static String load() {
        try (var stream = new ClassPathResource("ai/NOTES_GENERATION.md").getInputStream()) {
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            if (text.isBlank()) throw new IllegalStateException("Notes generation guide is empty.");
            return text;
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot load the notes generation system prompt.", ex);
        }
    }
}
