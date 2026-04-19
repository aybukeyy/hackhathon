package com.chatbot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OllamaConfig {

    @Value("${ollama.base-url:http://localhost:11434}")
    private String baseUrl;

    @Value("${ollama.model:gemma3:4b}")
    private String model;

    @Value("${ollama.timeout-ms:60000}")
    private int timeoutMs;


    public String getBaseUrl() { return baseUrl; }
    public String getModel() { return model; }
    public int getTimeoutMs() { return timeoutMs; }
}
