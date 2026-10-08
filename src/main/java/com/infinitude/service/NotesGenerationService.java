package com.infinitude.service;

import com.infinitude.ai.AiService;
import com.infinitude.ai.model.SectionAiResponse;
import com.infinitude.ai.prompt.SectionPromptBuilder;
import com.infinitude.ai.prompt.DifficultyProfile;
import com.infinitude.config.GeminiConfiguration;
import com.infinitude.exception.AiGenerationException;
import com.infinitude.exception.AiResponseValidationException;
import com.infinitude.model.*;
import com.infinitude.repository.NotesRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class NotesGenerationService {
    private static final Logger log = LoggerFactory.getLogger(NotesGenerationService.class);
    private final NotesService notes;
    private final NotesRepository repository;
    private final NoteWorkflowStore workflow;
    private final AiService ai;
    private final GeminiConfiguration config;
    private final SectionPromptBuilder prompts;
    private final ThreadPoolTaskExecutor executor;

    public NotesGenerationService(NotesService notes, NotesRepository repository,
            NoteWorkflowStore workflow, AiService ai, GeminiConfiguration config,
            SectionPromptBuilder prompts,
            @Qualifier("notesGenerationExecutor") ThreadPoolTaskExecutor executor) {
        this.notes = notes;
        this.repository = repository;
        this.workflow = workflow;
        this.ai = ai;
        this.config = config;
        this.prompts = prompts;
        this.executor = executor;
    }

    public Note generate(String noteId, String userId) {
        Note note = notes.getNote(noteId, userId);
        if (!note.isTocSaved()) {
            throw new IllegalStateException("Save the TOC before generating notes.");
        }
        TocStructure.numbering(note.getSections());
        DifficultyProfile.from(note.getDifficulty());
        if (config.keyPool().isEmpty()) {
            throw new IllegalStateException("Notes generation is unavailable: configure Gemini credentials on the server.");
        }
        note.setStatus(NotesStatus.GENERATING_NOTES);
        note.setMarkdownContent(null);
        for (Section section : note.getSections()) {
            section.setContent(null);
            section.setFailureReason(null);
            section.setStatus(SectionStatus.PENDING);
        }
        note.setUpdatedAt(Instant.now());
        Note claimed = workflow.replaceIdle(note, note.getTocRevision());
        try {
            executor.execute(() -> runGeneration(claimed));
        } catch (TaskRejectedException ex) {
            claimed.setStatus(NotesStatus.FAILED);
            claimed.setUpdatedAt(Instant.now());
            repository.save(claimed);
            log.warn("Notes generation queue full for note={}", noteId);
            throw new IllegalStateException("Notes generation is busy. Please retry shortly.");
        }
        // The worker mutates claimed; return a fresh snapshot, not a shared object.
        return notes.getNote(noteId, userId);
    }

    void generateSavedTopics(Note note) {
        boolean failed = false;
        for (int i = 0; i < note.getSections().size(); i++) {
            Section section = note.getSections().get(i);
            section.setStatus(SectionStatus.GENERATING);
            persist(note);
            try {
                SectionAiResponse response = generateValidatedTopic(note, i);
                section.setContent(format(response, section.getLevel()));
                section.setStatus(SectionStatus.COMPLETED);
            } catch (RuntimeException ex) {
                // Async boundary: every topic failure is persisted and remaining topics still run.
                failed = true;
                section.setStatus(SectionStatus.FAILED);
                section.setFailureReason(ex instanceof AiGenerationException
                        ? "AI could not produce complete notes for this topic. Retry generation."
                        : "Topic generation failed. Retry generation.");
                log.error("Notes topic failed note={} section={} type={}",
                        note.getId(), section.getSectionId(), ex.getClass().getSimpleName());
            }
            persist(note);
        }

        note.setStatus(failed ? NotesStatus.FAILED : NotesStatus.COMPLETED);
        note.setMarkdownContent(failed ? null : markdown(note));
        persist(note);
    }

    private void runGeneration(Note note) {
        try {
            generateSavedTopics(note);
        } catch (RuntimeException ex) {
            log.error("Notes job failed note={} type={}", note.getId(), ex.getClass().getSimpleName());
            note.setStatus(NotesStatus.FAILED);
            note.setMarkdownContent(null);
            for (Section section : note.getSections()) {
                if (section.getStatus() != SectionStatus.COMPLETED) {
                    section.setStatus(SectionStatus.FAILED);
                    section.setFailureReason("Generation was interrupted. Retry notes generation.");
                }
            }
            persist(note);
        }
    }

    private void persist(Note note) {
        note.setUpdatedAt(Instant.now());
        repository.save(note);
    }

    private SectionAiResponse generateValidatedTopic(Note note, int index) {
        String prompt = prompts.build(note, index);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                SectionAiResponse response = ai.generateSection(prompt,
                        config.keyPool().commaSeparatedKeys(), config.preferredModel());
                int minimum = DifficultyProfile.from(note.getDifficulty()).minimumExplanationWords();
                if (response == null || response.explanation() == null
                        || response.explanation().trim().split("\\s+").length < minimum
                        || response.subtopics() == null || response.subtopics().size() < 2
                        || response.subtopics().stream().anyMatch(subtopic -> subtopic == null
                        || subtopic.explanation() == null
                        || subtopic.explanation().trim().split("\\s+").length < minimum / 3)) {
                    throw new AiResponseValidationException("AI_GENERATION_FAILED: Notes lack required depth or supporting subtopics.");
                }
                return response;
            } catch (AiResponseValidationException ex) {
                if (attempt == 1) throw ex;
                log.warn("Retrying incomplete topic note={} section={}", note.getId(),
                        note.getSections().get(index).getSectionId());
                prompt += "\nThe previous response failed completeness validation. Return complete valid JSON, "
                        + "meet every word minimum and include 2-6 substantive supporting subtopics. "
                        + "Do not return a short summary or omit fields.";
            }
        }
        throw new IllegalStateException("Topic generation exhausted its bounded attempts.");
    }

    private String format(SectionAiResponse response, int level) {
        int detailLevel = Math.min(level + 1, 6);
        StringBuilder content = new StringBuilder(body(response.explanation(), response.keyConcepts(),
                response.examples(), detailLevel));
        for (var subtopic : response.subtopics()) {
            String title = subtopic.title().replaceAll("[\\r\\n]+", " ").trim();
            content.append("\n\n").append("#".repeat(detailLevel)).append(" ").append(title).append("\n\n")
                    .append(body(subtopic.explanation(), subtopic.keyConcepts(), subtopic.examples(),
                            Math.min(detailLevel + 1, 6)));
        }
        return content.toString();
    }

    private String body(String explanation, List<String> concepts, List<String> examples, int level) {
        String heading = "#".repeat(level);
        return explanation.trim() + "\n\n" + heading + " Key concepts\n\n"
                + String.join("\n", concepts.stream().map(s -> "- " + s.replace("\n", "\n  ")).toList())
                + "\n\n" + heading + " Examples\n\n" + String.join("\n\n", examples);
    }

    private String markdown(Note note) {
        List<String> numbers = TocStructure.numbering(note.getSections());
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < note.getSections().size(); i++) {
            Section section = note.getSections().get(i);
            result.append("#".repeat(section.getLevel())).append(" ")
                    .append(numbers.get(i)).append(" ").append(section.getTitle())
                    .append("\n\n").append(section.getContent()).append("\n\n");
        }
        return result.toString();
    }
}
