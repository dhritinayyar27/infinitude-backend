package com.infinitude.dto;

public class TocSectionDto {

    private String sectionId;
    private String title;
    private int order;

    public TocSectionDto() {
    }

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
