package com.infinitude.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Explicit list of FAILED topic IDs to regenerate. Only these topics are sent to the AI. */
public class RegenerateSectionsRequest {

    @NotEmpty(message = "Select at least one failed topic to regenerate.")
    @Size(max = 500, message = "Too many topics in one request.")
    private List<@NotBlank(message = "Topic IDs must be non-blank.") String> sectionIds;

    public RegenerateSectionsRequest() {
    }

    public RegenerateSectionsRequest(List<String> sectionIds) {
        this.sectionIds = sectionIds;
    }

    public List<String> getSectionIds() { return sectionIds; }
    public void setSectionIds(List<String> sectionIds) { this.sectionIds = sectionIds; }
}
