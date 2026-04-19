package com.chatbot.model;

public class ChatResponse {

    private String message;
    private String status;
    private String step;
    private Object routeData;

    public ChatResponse() {}

    public ChatResponse(String message, String status, String step) {
        this.message = message;
        this.status = status;
        this.step = step;
    }

    public static ChatResponse waitingInput(String message, String step) {
        return new ChatResponse(message, "WAITING_INPUT", step);
    }

    public static ChatResponse completed(String message, String step) {
        return new ChatResponse(message, "COMPLETED", step);
    }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getStep() { return step; }
    public void setStep(String step) { this.step = step; }

    public Object getRouteData() { return routeData; }
    public void setRouteData(Object routeData) { this.routeData = routeData; }
}
