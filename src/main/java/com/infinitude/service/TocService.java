package com.infinitude.service;

import com.infinitude.ai.AiService;
import com.infinitude.ai.model.TocAiResponse;
import com.infinitude.ai.model.TocSection;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class TocService {

    private final NotesRepository notesRepository;
    private final AiService aiService;
    private final String apiKey;
    private final String model;

    public TocService(NotesRepository notesRepository,
                      AiService aiService,
                      @Value("${infinitude.gemini.api-key:${GEMINI_API_KEY:}}") String apiKey,
                      @Value("${infinitude.gemini.model:gemini-2.5-flash}") String model) {
        this.notesRepository = notesRepository;
        this.aiService = aiService;
        this.apiKey = apiKey;
        this.model = model;
    }

    /**
     * Generates a Table of Contents via AI and saves it to the note.
     */
    public Note generateToc(String noteId, String userId) {
        Note note = loadAndCheckOwnership(noteId, userId);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("TOC generation is unavailable: configure GEMINI_API_KEY on the server.");
        }

        NotesStatus current = note.getStatus();
        if (current == NotesStatus.GENERATING_NOTES || current == NotesStatus.COMPLETED) {
            throw new IllegalStateException("Cannot regenerate TOC in current state: " + current);
        }

        note.setStatus(NotesStatus.GENERATING_TOC);
        note.setUpdatedAt(Instant.now());
        notesRepository.save(note);

        TocAiResponse tocResponse;
        try {
            tocResponse = aiService.generateTableOfContents(note.getTopic(), note.getDifficulty(), apiKey, model);
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
                section.setOrder(i + 1);
                section.setStatus(SectionStatus.PENDING);
                sections.add(section);
            }
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

        if (note.getStatus() == NotesStatus.GENERATING_NOTES) {
            throw new IllegalStateException("Cannot update TOC while notes are being generated.");
        }

        List<Section> existingSections = note.getSections();

        List<Section> updatedSections = new ArrayList<>();
        for (int i = 0; i < newSections.size(); i++) {
            TocSectionDto dto = newSections.get(i);
            Section section = null;

            // Try to find existing section to preserve content
            if (dto.getSectionId() != null && !dto.getSectionId().isBlank()) {
                section = existingSections.stream()
                        .filter(s -> dto.getSectionId().equals(s.getSectionId()))
                        .findFirst()
                        .orElse(null);
            }

            if (section == null) {
                section = new Section();
                section.setSectionId(new ObjectId().toString());
                section.setStatus(SectionStatus.PENDING);
            }

            section.setTitle(dto.getTitle());
            section.setOrder(i + 1);
            updatedSections.add(section);
        }

        note.setSections(updatedSections);
        if (note.getStatus() == NotesStatus.DRAFT || note.getStatus() == NotesStatus.GENERATING_TOC) {
            note.setStatus(NotesStatus.TOC_READY);
        }
        note.setUpdatedAt(Instant.now());
        return notesRepository.save(note);
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
