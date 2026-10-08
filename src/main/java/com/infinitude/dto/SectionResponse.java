package com.infinitude.dto;

public class SectionResponse {

    private String sectionId;
    private String title;
    private int order;
    private int level;

    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }
    private String content;
    private String status;
    private String failureReason;

    public SectionResponse() {
    }

    public SectionResponse(String sectionId, String title, int order,
                           String content, String status, String failureReason) {
        this.sectionId = sectionId;
        this.title = title;
        this.order = order;
        this.content = content;
        this.status = status;
        this.failureReason = failureReason;
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

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }
}
