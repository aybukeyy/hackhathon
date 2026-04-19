package com.chatbot.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ChatRequest {

    @NotBlank(message = "sessionId is required")
    @Size(max = 128)
    private String sessionId;

    @NotBlank(message = "message is required")
    @Size(max = 2000)
    private String message;

    private Double lat;
    private Double lng;

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Double getLat() { return lat; }
    public void setLat(Double lat) { this.lat = lat; }

    public Double getLng() { return lng; }
    public void setLng(Double lng) { this.lng = lng; }
}
