package com.infinitude.model;

/**
 * Embedded MongoDB document representing one section within a Note.
 * Not a top-level @Document — stored as a nested list inside Note.
 */
public class Section {

    private String sectionId;
    private String title;
    private int order;
    private int level = 1;
    private String content;
    private SectionStatus status = SectionStatus.PENDING;
    private String failureReason;

    public Section() {
    }

    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }

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

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public SectionStatus getStatus() {
        return status;
    }

    public void setStatus(SectionStatus status) {
        this.status = status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }
}
