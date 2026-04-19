package com.chatbot.model;

public class LLMResult {

    private String intent;
    private String entity;
    private double confidence;

    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }

    public String getEntity() { return entity; }
    public void setEntity(String entity) { this.entity = entity; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    @Override
    public String toString() {
        return "LLMResult{intent='" + intent + "', entity='" + entity + "', confidence=" + confidence + "}";
    }
}
