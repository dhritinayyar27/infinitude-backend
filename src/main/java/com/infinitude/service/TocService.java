package com.infinitude.service;

import com.infinitude.ai.AiService;
import com.infinitude.ai.model.TocAiResponse;
import com.infinitude.ai.model.TocSection;
import com.infinitude.config.GeminiConfiguration;
import com.infinitude.dto.TocSectionDto;
import com.infinitude.exception.AiGenerationException;
import com.infinitude.exception.NoteAccessDeniedException;
import com.infinitude.exception.NoteNotFoundException;
import com.infinitude.model.Note;
import com.infinitude.model.NotesStatus;
import com.infinitude.model.Section;
import com.infinitude.model.SectionStatus;
import com.infinitude.repository.NotesRepository;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

@Service
public class TocService {

    private final NotesRepository notesRepository;
    private final AiService aiService;
    private final GeminiConfiguration geminiConfiguration;
    private final NoteWorkflowStore workflowStore;

    public TocService(NotesRepository notesRepository,
                      AiService aiService,
                      GeminiConfiguration geminiConfiguration,
                      NoteWorkflowStore workflowStore) {
        this.notesRepository = notesRepository;
        this.aiService = aiService;
        this.geminiConfiguration = geminiConfiguration;
        this.workflowStore = workflowStore;
    }

    /**
     * Generates a Table of Contents via AI and saves it to the note.
     */
    public Note generateToc(String noteId, String userId) {
        Note note = loadAndCheckOwnership(noteId, userId);
        if (geminiConfiguration.keyPool().isEmpty()) {
            throw new IllegalStateException("TOC generation is unavailable: configure Gemini credentials on the server.");
        }

        NotesStatus current = note.getStatus();
        if (current == NotesStatus.GENERATING_NOTES || current == NotesStatus.GENERATING_TOC) {
            throw new IllegalStateException("Cannot regenerate TOC in current state: " + current);
        }

        long revision = note.getTocRevision();
        note.setTocRevision(revision + 1);
        note.setTocSaved(false);
        note.setMarkdownContent(null);
        note.setStatus(NotesStatus.GENERATING_TOC);
        note.setUpdatedAt(Instant.now());
        note = workflowStore.replaceIdle(note, revision);

        TocAiResponse tocResponse;
        try {
            tocResponse = aiService.generateTableOfContents(note.getTopic(), note.getDifficulty(),
                    geminiConfiguration.keyPool().commaSeparatedKeys(), geminiConfiguration.preferredModel());
        } catch (AiGenerationException e) {
            note.setStatus(NotesStatus.FAILED);
            note.setUpdatedAt(Instant.now());
            notesRepository.save(note);
            throw e;
        }

        // Convert AI response to Section objects
        List<Section> sections = new ArrayList<>();
        List<TocSection> aiSections = tocResponse.getSections();
        if (aiSections != null) {
            for (int i = 0; i < aiSections.size(); i++) {
                TocSection aiSection = aiSections.get(i);
                Section section = new Section();
                section.setSectionId(new ObjectId().toString());
                section.setTitle(aiSection.getTitle());
                section.setOrder(sections.size() + 1);
                section.setStatus(SectionStatus.PENDING);
                sections.add(section);
                if (aiSection.getSubsections() != null) {
                    for (String title : aiSection.getSubsections()) {
                        Section child = new Section();
                        child.setSectionId(new ObjectId().toString());
                        child.setTitle(title);
                        child.setLevel(2);
                        child.setOrder(sections.size() + 1);
                        sections.add(child);
                    }
                }
            }
        }

        try {
            TocStructure.numbering(sections);
        } catch (IllegalArgumentException ex) {
            note.setStatus(NotesStatus.FAILED);
            note.setUpdatedAt(Instant.now());
            notesRepository.save(note);
            throw new AiGenerationException("AI_GENERATION_FAILED: Invalid TOC structure.");
        }
        note.setSections(sections);
        note.setStatus(NotesStatus.TOC_READY);
        note.setUpdatedAt(Instant.now());
        return notesRepository.save(note);
    }

    /**
     * Returns the note with its TOC sections.
     */
    public Note getToc(String noteId, String userId) {
        return loadAndCheckOwnership(noteId, userId);
    }

    /**
     * Updates the TOC sections (allows editing/reordering before generation).
     */
    public Note updateToc(String noteId, String userId, List<TocSectionDto> newSections) {
        Note note = loadAndCheckOwnership(noteId, userId);

        if (note.getStatus() == NotesStatus.GENERATING_NOTES
                || note.getStatus() == NotesStatus.GENERATING_TOC) {
            throw new IllegalStateException("Cannot update TOC while notes are being generated.");
        }

        List<Section> updatedSections = new ArrayList<>();
        Set<String> existingIds = new HashSet<>();
        for (Section section : note.getSections()) existingIds.add(section.getSectionId());
        Set<String> usedIds = new HashSet<>();
        for (int i = 0; i < newSections.size(); i++) {
            TocSectionDto dto = newSections.get(i);
            if (dto == null || dto.getTitle() == null || dto.getTitle().isBlank()) {
                throw new IllegalArgumentException("All TOC titles must be non-blank.");
            }
            Section section = new Section();
            String id = dto.getSectionId();
            section.setSectionId(id != null && existingIds.contains(id) && usedIds.add(id)
                    ? id : new ObjectId().toString());
            section.setTitle(dto.getTitle().trim());
            section.setLevel(dto.getLevel());
            section.setOrder(i + 1);
            updatedSections.add(section);
        }

        TocStructure.numbering(updatedSections);
        long revision = note.getTocRevision();
        note.setTocRevision(revision + 1);
        note.setTocSaved(true);
        note.setMarkdownContent(null);
        note.setMarkdownFilePath(null);
        note.setSections(updatedSections);
        note.setStatus(NotesStatus.TOC_READY);
        note.setUpdatedAt(Instant.now());
        return workflowStore.replaceIdle(note, revision);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Note loadAndCheckOwnership(String noteId, String userId) {
        Note note = notesRepository.findById(noteId)
                .orElseThrow(() -> new NoteNotFoundException(noteId));
        if (!userId.equals(note.getUserId())) {
            throw new NoteAccessDeniedException();
        }
        return note;
    }

}
