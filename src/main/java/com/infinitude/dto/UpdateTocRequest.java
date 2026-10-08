package com.infinitude.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;

public class UpdateTocRequest {

    @NotNull
    @NotEmpty
    @Size(max = 100)
    private List<@NotNull @Valid TocSectionDto> sections;

    public UpdateTocRequest() {
    }

    public List<TocSectionDto> getSections() {
        return sections;
    }

    public void setSections(List<TocSectionDto> sections) {
        this.sections = sections;
    }
}
