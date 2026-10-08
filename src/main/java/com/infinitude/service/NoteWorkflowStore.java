package com.infinitude.service;

import com.infinitude.model.Note;
import com.infinitude.model.NotesStatus;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

@Component
public class NoteWorkflowStore {
    private final MongoTemplate mongo;

    public NoteWorkflowStore(MongoTemplate mongo) { this.mongo = mongo; }

    public Note replaceIdle(Note note, long expectedRevision) {
        Criteria revision = expectedRevision == 0
                ? new Criteria().orOperator(Criteria.where("tocRevision").is(0),
                    Criteria.where("tocRevision").exists(false))
                : Criteria.where("tocRevision").is(expectedRevision);
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(note.getId()),
                Criteria.where("userId").is(note.getUserId()),
                Criteria.where("status").nin(NotesStatus.GENERATING_TOC, NotesStatus.GENERATING_NOTES),
                revision));
        Note updated = mongo.findAndReplace(query, note,
                FindAndReplaceOptions.options().returnNew());
        if (updated == null) {
            throw new IllegalStateException("The note changed or generation is already running. Reload and try again.");
        }
        return updated;
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
