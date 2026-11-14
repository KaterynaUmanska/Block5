package org.example.block5.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public class EmailMessage {

    @NotBlank
    private String subject;

    @NotBlank
    private String content;

    @NotBlank
    @Email
    private String recipient;

    private String source;

    private String sourceId;

    public EmailMessage() {
    }

    public EmailMessage(
            String subject,
            String content,
            String recipient,
            String source,
            String sourceId) {

        this.subject = subject;
        this.content = content;
        this.recipient = recipient;
        this.source = source;
        this.sourceId = sourceId;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getRecipient() {
        return recipient;
    }

    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }
}