package com.infinitude.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonCreator;

public class TocSectionDto {

    private String sectionId;
    @NotBlank
    @Size(max = 300)
    private String title;
    private int order;
    @Min(1)
    @Max(5)
    private int level = 1;

    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }

    public TocSectionDto() {
    }

    @JsonCreator(mode = JsonCreator.Mode.DISABLED)
    public TocSectionDto(String sectionId, String title, int order) {
        this.sectionId = sectionId;
        this.title = title;
        this.order = order;
    }

    public String getSectionId() {
        return sectionId;
    }

    public void setSectionId(String sectionId) {
        this.sectionId = sectionId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public int getOrder() {
        return order;
    }

    public void setOrder(int order) {
        this.order = order;
    }
}
