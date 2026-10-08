package com.infinitude.exception;

public class SectionNotFoundException extends RuntimeException {
    public SectionNotFoundException(String sectionId) {
        super("TOC section not found: " + sectionId);
    }
}
