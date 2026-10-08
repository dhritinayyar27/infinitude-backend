package com.infinitude.service;

import com.infinitude.ai.AiService;
import com.infinitude.ai.model.SectionAiResponse;
import com.infinitude.ai.prompt.SectionPromptBuilder;
import com.infinitude.ai.prompt.DifficultyProfile;
import com.infinitude.config.GeminiConfiguration;
import com.infinitude.exception.AiGenerationException;
import com.infinitude.exception.AiResponseValidationException;
import com.infinitude.exception.SectionNotFoundException;
import com.infinitude.model.*;
import com.infinitude.repository.NotesRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class NotesGenerationService {
    private static final Logger log = LoggerFactory.getLogger(NotesGenerationService.class);
    private static final String BUSY_REASON = "The generation queue was full. Regenerate this topic shortly.";
    private static final int MAX_QUALITY_RETRIES = 1;

    interface Sleeper { void sleep(long millis) throws InterruptedException; }

    private Sleeper sleeper = Thread::sleep;

    void setSleeper(Sleeper sleeper) { this.sleeper = sleeper; }
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

    /**
     * Full generation of every saved topic. A note with persisted FAILED topics is never sent
     * through this path: it is redirected to {@link #regenerateFailedSections} so completed
     * topics cannot be reset or regenerated.
     */
    public Note generate(String noteId, String userId) {
        Note note = notes.getNote(noteId, userId);
        requireGeneratable(note);
        if (note.getStatus() == NotesStatus.FAILED) {
            List<String> failed = failedSectionIds(note);
            if (failed.isEmpty()) {
                throw new IllegalStateException("This note has no failed topics to regenerate. Reload and try again.");
            }
            return regenerateFailedSections(noteId, failed, userId);
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
        List<String> all = claimed.getSections().stream().map(Section::getSectionId).toList();
        schedule(claimed, Set.copyOf(all));
        // The worker mutates claimed; return a fresh snapshot, not a shared object.
        return notes.getNote(noteId, userId);
    }

    /** Regenerates one FAILED topic. Equivalent to {@link #regenerateFailedSections} with one ID. */
    public Note regenerateSection(String noteId, String sectionId, String userId) {
        return regenerateFailedSections(noteId, List.of(sectionId), userId);
    }

    /**
     * Incremental regeneration on the persisted topic state: only the requested topics, each of
     * which must currently be FAILED, are claimed and sent to the AI. Completed topics are never
     * written, reset, or used as generation targets; they only provide continuity context, exactly
     * as in the original generation.
     */
    public Note regenerateFailedSections(String noteId, List<String> sectionIds, String userId) {
        Note note = notes.getNote(noteId, userId);
        if (sectionIds == null || sectionIds.isEmpty()) {
            throw new IllegalArgumentException("Select at least one failed topic to regenerate.");
        }
        Set<String> targets = new LinkedHashSet<>();
        for (String sectionId : sectionIds) {
            if (sectionId == null || sectionId.isBlank()) {
                throw new IllegalArgumentException("Topic IDs must be non-blank.");
            }
            targets.add(sectionId);
        }
        List<Section> selected = targets.stream().map(id -> findSection(note, id)).toList();
        if (note.getStatus() == NotesStatus.GENERATING_NOTES || note.getStatus() == NotesStatus.GENERATING_TOC) {
            throw new IllegalStateException("Generation is already running for this note. Wait for it to finish.");
        }
        for (Section section : selected) {
            if (section.getStatus() != SectionStatus.FAILED) {
                throw new IllegalStateException("Only failed topics can be regenerated. \""
                        + section.getTitle() + "\" is " + section.getStatus().name().toLowerCase() + ".");
            }
        }
        requireGeneratable(note);
        Note claimed = workflow.claimFailedSections(note, targets);
        log.info("Regenerating failed topics note={} count={}", noteId, targets.size());
        schedule(claimed, targets);
        return notes.getNote(noteId, userId);
    }

    private void schedule(Note claimed, Set<String> targets) {
        try {
            executor.execute(() -> runTopics(claimed, targets));
        } catch (TaskRejectedException ex) {
            claimed.setStatus(NotesStatus.FAILED);
            for (Section section : claimed.getSections()) {
                if (targets.contains(section.getSectionId())) {
                    section.setStatus(SectionStatus.FAILED);
                    section.setContent(null);
                    section.setFailureReason(BUSY_REASON);
                }
            }
            claimed.setUpdatedAt(Instant.now());
            repository.save(claimed);
            log.warn("Notes generation queue full for note={}", claimed.getId());
            throw new IllegalStateException("Notes generation is busy. Please retry shortly.");
        }
    }

    /** Full-generation entry point used by tests: every topic is a target. */
    void generateSavedTopics(Note note) {
        generateTopics(note, note.getSections().stream().map(Section::getSectionId)
                .collect(java.util.stream.Collectors.toSet()));
    }

    /**
     * Generates exactly the target topics, in TOC order. Non-target topics are skipped without
     * being read for status, written, or sent to the AI.
     */
    void generateTopics(Note note, Set<String> targets) {
        String credentialFailure = null;
        for (int i = 0; i < note.getSections().size(); i++) {
            Section section = note.getSections().get(i);
            if (!targets.contains(section.getSectionId())) {
                continue;
            }
            if (credentialFailure != null) {
                // Every key was rejected; further calls are guaranteed to fail the same way.
                section.setStatus(SectionStatus.FAILED);
                section.setContent(null);
                section.setFailureReason(credentialFailure);
                continue;
            }
            section.setStatus(SectionStatus.GENERATING);
            persist(note);
            RuntimeException failure = generateTopic(note, i);
            if (failure != null && isCredentialFailure(failure)) {
                credentialFailure = section.getFailureReason();
            }
            persist(note);
        }
        finish(note);
    }

    private void runTopics(Note note, Set<String> targets) {
        try {
            generateTopics(note, targets);
        } catch (RuntimeException ex) {
            log.error("Notes job failed note={} type={}", note.getId(), ex.getClass().getSimpleName());
            failInterrupted(note);
        }
    }

    private static List<String> failedSectionIds(Note note) {
        return note.getSections().stream()
                .filter(section -> section.getStatus() == SectionStatus.FAILED)
                .map(Section::getSectionId).toList();
    }

    private void failInterrupted(Note note) {
        note.setStatus(NotesStatus.FAILED);
        note.setMarkdownContent(null);
        for (Section section : note.getSections()) {
            if (section.getStatus() != SectionStatus.COMPLETED) {
                section.setStatus(SectionStatus.FAILED);
                section.setContent(null);
                section.setFailureReason(NoteWorkflowStore.INTERRUPTED_REASON);
            }
        }
        persist(note);
    }

    /**
     * Generates, validates and formats one topic in place. Content is only stored after it has
     * passed validation and formatting; any failure leaves the topic FAILED with a specific reason.
     */
    private RuntimeException generateTopic(Note note, int index) {
        Section section = note.getSections().get(index);
        try {
            SectionAiResponse response = generateValidatedTopic(note, index);
            String content = format(response, section.getLevel());
            if (content.isBlank()) {
                throw new AiResponseValidationException("AI_GENERATION_FAILED: Formatted topic content is empty.");
            }
            section.setContent(content);
            section.setFailureReason(null);
            section.setStatus(SectionStatus.COMPLETED);
            return null;
        } catch (RuntimeException ex) {
            // Async boundary: every topic failure is persisted and remaining topics still run.
            section.setContent(null);
            section.setStatus(SectionStatus.FAILED);
            section.setFailureReason(failureReason(ex));
            log.error("Notes topic failed note={} section={} type={}",
                    note.getId(), section.getSectionId(), ex.getClass().getSimpleName());
            return ex;
        }
    }

    private void finish(Note note) {
        boolean complete = note.getSections().stream().allMatch(section ->
                section.getStatus() == SectionStatus.COMPLETED && section.getContent() != null);
        note.setStatus(complete ? NotesStatus.COMPLETED : NotesStatus.FAILED);
        note.setMarkdownContent(complete ? markdown(note) : null);
        persist(note);
    }

    private void requireGeneratable(Note note) {
        if (!note.isTocSaved()) {
            throw new IllegalStateException("Save the TOC before generating notes.");
        }
        TocStructure.numbering(note.getSections());
        DifficultyProfile.from(note.getDifficulty());
        if (config.keyPool().isEmpty()) {
            throw new IllegalStateException("Notes generation is unavailable: configure Gemini credentials on the server.");
        }
    }

    private static Section findSection(Note note, String sectionId) {
        return note.getSections().stream()
                .filter(section -> sectionId.equals(section.getSectionId()))
                .findFirst().orElseThrow(() -> new SectionNotFoundException(sectionId));
    }

    private static boolean isCredentialFailure(RuntimeException ex) {
        return ex instanceof AiGenerationException && !(ex instanceof AiResponseValidationException)
                && ex.getMessage() != null && ex.getMessage().startsWith("INVALID_API_KEY");
    }

    static String failureReason(RuntimeException ex) {
        if (ex instanceof AiResponseValidationException) {
            return "The AI returned incomplete or invalid notes for this topic. Regenerate to try again.";
        }
        if (ex instanceof AiGenerationException) {
            String message = ex.getMessage() == null ? "" : ex.getMessage();
            if (message.startsWith("AI_GENERATION_TIMEOUT")) {
                return "The AI service took too long to respond. Regenerate to try again.";
            }
            if (message.startsWith("QUOTA_EXCEEDED")) {
                return "The AI usage limit was reached. Wait a minute, then regenerate this topic.";
            }
            if (message.startsWith("INVALID_API_KEY")) {
                return "The AI service rejected the server credentials. Contact the administrator, then regenerate.";
            }
            if (message.startsWith("AI_GENERATION_BLOCKED")) {
                return "The AI declined to write this topic. Regenerate, or reword the topic in the TOC.";
            }
            return "The AI service could not produce notes for this topic. Regenerate to try again.";
        }
        return "Topic generation failed unexpectedly. Regenerate to try again.";
    }

    private void persist(Note note) {
        note.setUpdatedAt(Instant.now());
        repository.save(note);
    }

    /**
     * Calls the AI for one topic only. Bounded retries:
     * - 1 quality-correction retry for malformed/incomplete output;
     * - transient transport failures (timeout, 5xx, network, quota, all models busy) are retried
     *   with exponential backoff (see {@link #transientBackoffMs}).
     * Credential, blocked and other client errors are never retried.
     */
    private SectionAiResponse generateValidatedTopic(Note note, int index) {
        String prompt = prompts.build(note, index);
        String sectionId = note.getSections().get(index).getSectionId();
        int qualityRetries = 0;
        int transientRetries = 0;
        while (true) {
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
                if (++qualityRetries > MAX_QUALITY_RETRIES) throw ex;
                log.warn("Retrying incomplete topic note={} section={}", note.getId(), sectionId);
                prompt += "\nThe previous response failed completeness validation. Return complete valid JSON, "
                        + "meet every word minimum and include 2-6 substantive supporting subtopics. "
                        + "Do not return a short summary or omit fields.";
            } catch (AiGenerationException ex) {
                long delay = transientBackoffMs(ex, transientRetries);
                if (delay < 0) throw ex;
                transientRetries++;
                log.warn("Retrying transient AI failure note={} section={} retry={} backoffMs={}",
                        note.getId(), sectionId, transientRetries, delay);
                try {
                    sleeper.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
    }

    /** Backoff before the next transient retry, or -1 when the failure must not be retried. */
    static long transientBackoffMs(AiGenerationException ex, int retriesSoFar) {
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        if (message.startsWith("AI_GENERATION_TIMEOUT")) {
            // Each attempt can already take the full notes read timeout; retry once only.
            return retriesSoFar < 1 ? 2_000 : -1;
        }
        if (message.startsWith("QUOTA_EXCEEDED")) {
            // Per-minute quotas usually recover; wait long enough for the window to move.
            return retriesSoFar < 1 ? 20_000 : -1;
        }
        if (message.startsWith("AI_GENERATION_FAILED: HTTP 5")
                || message.startsWith("AI_GENERATION_FAILED: Unable to read or reach")
                || message.startsWith("AI_GENERATION_FAILED: All candidate models are unavailable")) {
            return retriesSoFar < 2 ? 2_000L << retriesSoFar : -1;
        }
        return -1;
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
