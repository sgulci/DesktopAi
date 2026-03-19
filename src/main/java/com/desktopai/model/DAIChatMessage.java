package com.desktopai.model;

import java.time.LocalDateTime;

/**
 * Represents a chat message in a conversation.
 */
public class DAIChatMessage {
    private Long id;
    private String sessionId;
    private Role role;
    private String content;
    private String modelUsed;
    private String providerId;
    private LocalDateTime timestamp;

    public enum Role {
        USER, ASSISTANT, SYSTEM
    }

    public DAIChatMessage() {
        this.timestamp = LocalDateTime.now();
    }

    public DAIChatMessage(String sessionId, Role role, String content) {
        this();
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
    }

    // Getters and setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getModelUsed() {
        return modelUsed;
    }

    public void setModelUsed(String modelUsed) {
        this.modelUsed = modelUsed;
    }

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }
}
