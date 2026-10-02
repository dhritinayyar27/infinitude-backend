package com.infinitude.service;

import com.infinitude.exception.NoteAccessDeniedException;
import com.infinitude.exception.NoteNotFoundException;
import com.infinitude.model.Note;
import com.infinitude.repository.NotesRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class NotesService {

    private final NotesRepository notesRepository;
    public NotesService(NotesRepository notesRepository) {
        this.notesRepository = notesRepository;
    }

    /**
     * Creates a new note with DRAFT status. Title defaults to the topic.
     */
    public Note createNote(String userId, String topic, String difficulty, String style) {
        Note note = new Note(userId, topic, topic, difficulty, style);
        return notesRepository.save(note);
    }

    /**
     * Lists all notes for the user, newest first.
     */
    public List<Note> listNotes(String userId) {
        return notesRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * Gets a specific note after verifying ownership.
     */
    public Note getNote(String noteId, String userId) {
        Note note = notesRepository.findById(noteId)
                .orElseThrow(() -> new NoteNotFoundException(noteId));
        if (!userId.equals(note.getUserId())) {
            throw new NoteAccessDeniedException();
        }
        return note;
    }

    /**
     * Deletes a note after verifying ownership.
     */
    public void deleteNote(String noteId, String userId) {
        Note note = getNote(noteId, userId);
        notesRepository.delete(note);
    }

}
