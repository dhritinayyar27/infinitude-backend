package com.infinitude.exception;

public class NoteNotFoundException extends RuntimeException {
    public NoteNotFoundException(String noteId) {
        super("Note not found: " + noteId);
    }
}
