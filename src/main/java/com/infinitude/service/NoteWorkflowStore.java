package com.infinitude.service;

import com.infinitude.model.Note;
import com.infinitude.model.NotesStatus;
import com.infinitude.model.Section;
import com.infinitude.model.SectionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Component
public class NoteWorkflowStore {
    static final String INTERRUPTED_REASON =
            "Generation was interrupted before this topic finished. Regenerate this topic.";
    private static final Logger log = LoggerFactory.getLogger(NoteWorkflowStore.class);
    private final MongoTemplate mongo;

    public NoteWorkflowStore(MongoTemplate mongo) { this.mongo = mongo; }

    public Note replaceIdle(Note note, long expectedRevision) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(note.getId()),
                Criteria.where("userId").is(note.getUserId()),
                Criteria.where("status").nin(NotesStatus.GENERATING_TOC, NotesStatus.GENERATING_NOTES),
                revision(expectedRevision)));
        Note updated = mongo.findAndReplace(query, note,
                FindAndReplaceOptions.options().returnNew());
        if (updated == null) {
            throw new IllegalStateException("The note changed or generation is already running. Reload and try again.");
        }
        return updated;
    }

    /**
     * Atomically locks the note for generation and moves exactly the given sections from FAILED
     * to GENERATING. The match requires every target to still be FAILED, and the update writes
     * only those array elements (via arrayFilters), so completed sections are never modified and
     * a duplicate click cannot claim the same sections twice.
     */
    public Note claimFailedSections(Note note, Collection<String> sectionIds) {
        List<Criteria> criteria = new ArrayList<>(List.of(
                Criteria.where("_id").is(note.getId()),
                Criteria.where("userId").is(note.getUserId()),
                Criteria.where("tocSaved").is(true),
                Criteria.where("status").nin(NotesStatus.GENERATING_TOC, NotesStatus.GENERATING_NOTES),
                revision(note.getTocRevision())));
        for (String sectionId : sectionIds) {
            criteria.add(Criteria.where("sections").elemMatch(Criteria.where("sectionId").is(sectionId)
                    .and("status").is(SectionStatus.FAILED.name())));
        }
        Update update = new Update()
                .set("status", NotesStatus.GENERATING_NOTES.name())
                .set("markdownContent", null)
                .set("updatedAt", Instant.now())
                .set("sections.$[target].status", SectionStatus.GENERATING.name())
                .set("sections.$[target].content", null)
                .set("sections.$[target].failureReason", null)
                .filterArray(Criteria.where("target.sectionId").in(sectionIds)
                        .and("target.status").is(SectionStatus.FAILED.name()));
        Note updated = mongo.findAndModify(Query.query(new Criteria().andOperator(criteria)), update,
                FindAndModifyOptions.options().returnNew(true), Note.class);
        if (updated == null) {
            throw new IllegalStateException(
                    "These topics are no longer failed or the note changed. Reload and try again.");
        }
        return updated;
    }

    /**
     * Single-instance recovery: workers do not survive a restart, so any note still marked as
     * generating at startup is orphaned. Saved-TOC notes left FAILED with waiting/generating
     * topics (older builds) are normalised too. Unfinished topics become FAILED, completed ones
     * are kept, so the user can regenerate only what is missing.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterrupted() {
        try {
            List<Note> stuck = mongo.find(Query.query(new Criteria().orOperator(
                    Criteria.where("status").in(NotesStatus.GENERATING_TOC, NotesStatus.GENERATING_NOTES),
                    Criteria.where("status").is(NotesStatus.FAILED).and("tocSaved").is(true)
                            .and("sections.status").in(SectionStatus.PENDING, SectionStatus.GENERATING))),
                    Note.class);
            for (Note note : stuck) {
                boolean notes = note.getStatus() != NotesStatus.GENERATING_TOC;
                note.setStatus(NotesStatus.FAILED);
                note.setMarkdownContent(null);
                if (notes) {
                    for (Section section : note.getSections()) {
                        if (section.getStatus() != SectionStatus.COMPLETED) {
                            section.setStatus(SectionStatus.FAILED);
                            section.setContent(null);
                            section.setFailureReason(INTERRUPTED_REASON);
                        }
                    }
                }
                note.setUpdatedAt(Instant.now());
                mongo.save(note);
            }
            if (!stuck.isEmpty()) {
                log.warn("Recovered {} note(s) left generating by a previous shutdown", stuck.size());
            }
        } catch (RuntimeException ex) {
            log.warn("Could not recover interrupted generations type={}", ex.getClass().getSimpleName());
        }
    }

    private static Criteria revision(long expectedRevision) {
        return expectedRevision == 0
                ? new Criteria().orOperator(Criteria.where("tocRevision").is(0),
                    Criteria.where("tocRevision").exists(false))
                : Criteria.where("tocRevision").is(expectedRevision);
    }

    public void deleteIdle(Note note) {
        Note deleted = mongo.findAndRemove(Query.query(Criteria.where("_id").is(note.getId())
                .and("userId").is(note.getUserId())
                .and("status").nin(NotesStatus.GENERATING_TOC, NotesStatus.GENERATING_NOTES)), Note.class);
        if (deleted == null) {
            throw new IllegalStateException("Cannot delete a note during generation. Reload and try again.");
        }
    }
}
