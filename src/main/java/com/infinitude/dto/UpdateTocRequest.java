package com.infinitude.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public class UpdateTocRequest {

    @NotNull
    @NotEmpty
    private List<TocSectionDto> sections;

    public UpdateTocRequest() {
    }

    public List<TocSectionDto> getSections() {
        return sections;
    }

    public void setSections(List<TocSectionDto> sections) {
        this.sections = sections;
    }
}
