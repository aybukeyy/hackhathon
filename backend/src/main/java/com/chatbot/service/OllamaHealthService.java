package com.chatbot.service;

import com.chatbot.config.OllamaConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class OllamaHealthService {

    private static final Logger log = LoggerFactory.getLogger(OllamaHealthService.class);

    private final OllamaConfig ollamaConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private volatile String resolvedModel;

    public OllamaHealthService(OllamaConfig ollamaConfig) {
        this.ollamaConfig = ollamaConfig;
    }

    public void validateOnStartup() {
        log.info("Checking Ollama at {}", ollamaConfig.getBaseUrl());
        List<String> models = fetchAvailableModels();

        if (models.isEmpty()) {
            throw new IllegalStateException(
                    "No Ollama models installed. Run: ollama pull " + ollamaConfig.getModel());
        }

        String preferred = ollamaConfig.getModel();
        if (models.contains(preferred)) {
            resolvedModel = preferred;
            log.info("Using model: {}", resolvedModel);
        } else {
            resolvedModel = models.get(0);
            log.warn("Model '{}' not found — falling back to '{}'", preferred, resolvedModel);
        }
    }

    public String getResolvedModel() {
        return resolvedModel != null ? resolvedModel : ollamaConfig.getModel();
    }

    public List<String> fetchAvailableModels() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaConfig.getBaseUrl() + "/api/tags"))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode modelsNode = root.path("models");

            List<String> names = new ArrayList<>();
            if (modelsNode.isArray()) {
                for (JsonNode model : modelsNode) {
                    names.add(model.path("name").asText());
                }
            }
            return names;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Ollama is not running at " + ollamaConfig.getBaseUrl() +
                    ". Start it with: ollama serve", e);
        }
    }

    public boolean isOllamaReachable() {
        try {
            fetchAvailableModels();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
