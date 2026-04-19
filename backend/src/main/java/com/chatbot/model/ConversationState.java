package com.chatbot.model;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

public class ConversationState {

    private String sessionId;
    private String intent;
    private String entity;
    private FlowStep step;
    private Map<String, Object> data = new HashMap<>();
    private LocalDateTime lastUpdated = LocalDateTime.now();

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }

    public String getEntity() { return entity; }
    public void setEntity(String entity) { this.entity = entity; }

    public FlowStep getStep() { return step; }
    public void setStep(FlowStep step) {
        this.step = step;
        this.lastUpdated = LocalDateTime.now();
    }

    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }

    public LocalDateTime getLastUpdated() { return lastUpdated; }
}
