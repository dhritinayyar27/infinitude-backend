package com.infinitude.ai;

import com.infinitude.ai.prompt.*;
import com.infinitude.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PromptBuilderTests {
    private final SectionPromptBuilder sections = new SectionPromptBuilder(new ObjectMapper());
    private final TocPromptBuilder toc = new TocPromptBuilder();

    @ParameterizedTest
    @EnumSource(DifficultyProfile.class)
    void selectedDifficultyControlsBothStages(DifficultyProfile profile) {
        Note note = note(profile.name());
        String outlinePrompt = toc.build(note.getTopic(), note.getDifficulty());
        String notesPrompt = sections.build(note, 1);
        assertTrue(outlinePrompt.contains(profile.guidance()));
        assertTrue(notesPrompt.contains(profile.guidance()));
        assertTrue(outlinePrompt.contains("between " + profile.minSections() + " and " + profile.maxSections()));
        assertTrue(notesPrompt.contains("in " + profile.targetWords() + " words"));
        assertTrue(notesPrompt.contains("at least " + profile.minimumExplanationWords() + " words"));
        assertTrue(notesPrompt.contains("hierarchy level is not difficulty"));
        assertTrue(outlinePrompt.contains("at or below 100"));
    }

    @Test
    void ancestorPathExcludesUnrelatedBranchAndExcerptsIncludeOnlyCompletedTopics() {
        Note note = note("BEGINNER");
        note.getSections().get(0).setContent("Shared definitions and shopping-cart example.");
        note.getSections().get(0).setStatus(SectionStatus.COMPLETED);
        note.getSections().get(1).setContent("Failed topic content must not be context.");
        note.getSections().get(1).setStatus(SectionStatus.FAILED);
        String prompt = sections.build(note, 2);
        String path = prompt.lines().filter(line -> line.startsWith("Current topic's ancestor path")).findFirst().orElseThrow();
        assertTrue(path.contains("Java Basics"));
        assertTrue(path.contains("Data Types"));
        assertFalse(path.contains("Variables"));
        assertTrue(prompt.contains("Shared definitions and shopping-cart example."));
        assertFalse(prompt.contains("Failed topic content must not be context."));
        assertTrue(prompt.contains("Preserve terminology, definitions, notation and example domain"));
    }

    @Test
    void contextIsBoundedToThreeRecentExcerptsOf1200Characters() {
        Note note = note("ADVANCED");
        note.setSections(new ArrayList<>());
        for (int i = 0; i < 5; i++) {
            Section section = new Section();
            section.setTitle("Topic " + i);
            section.setStatus(SectionStatus.COMPLETED);
            section.setContent("marker-" + i + " " + "x".repeat(2000));
            note.getSections().add(section);
        }
        String prompt = sections.build(note, 4);
        String context = prompt.lines().filter(line -> line.startsWith("Recent completed topic excerpts")).findFirst().orElseThrow();
        assertFalse(context.contains("marker-0"));
        assertTrue(context.contains("marker-1"));
        assertTrue(context.contains("marker-2"));
        assertTrue(context.contains("marker-3"));
        assertFalse(context.contains("marker-4"));
        assertFalse(context.contains("x".repeat(1200)));
        assertTrue(context.indexOf("marker-1") < context.indexOf("marker-3"));
        assertTrue(context.length() < 3900);
        String anchor = prompt.lines().filter(line -> line.startsWith("Continuity anchor")).findFirst().orElseThrow();
        assertTrue(anchor.contains("marker-0"));
        assertTrue(anchor.contains("x".repeat(395)));
        assertTrue(anchor.length() < 1350);
    }

    @Test
    void invalidDifficultyIsNotSilentlyMappedToAnotherLevel() {
        assertThrows(IllegalArgumentException.class, () -> toc.build("Java", "EXPERT"));
        assertThrows(IllegalArgumentException.class, () -> sections.build(note(null), 0));
    }

    @Test
    void parentRequestIncludesAllSavedDescendantsAndSupportingCoverageRequirements() {
        String prompt = sections.build(note("INTERMEDIATE"), 0);
        String descendants = prompt.lines().filter(line -> line.startsWith("Saved descendants")).findFirst().orElseThrow();
        assertTrue(descendants.contains("Variables"));
        assertTrue(descendants.contains("Data Types"));
        assertTrue(prompt.contains("2-6 essential supporting subtopics"));
        assertTrue(prompt.contains("at least 60 explanation words"));
        assertTrue(prompt.contains("Use $...$ and $$...$$ for math"));
        assertTrue(prompt.contains("Markdown fragments"));
    }

    private Note note(String difficulty) {
        Note note = new Note("user", "Java", "Java", difficulty, "DETAILED");
        List<Section> topics = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Section section = new Section();
            section.setTitle(List.of("Java Basics", "Variables", "Data Types").get(i));
            section.setLevel(i == 0 ? 1 : 2);
            topics.add(section);
        }
        note.setSections(topics);
        return note;
    }
}
