package com.infinitude.exception;

public class NoteAccessDeniedException extends RuntimeException {
    public NoteAccessDeniedException() {
        super("You do not have access to this note.");
    }
}
