package com.chatbot.service;

import com.chatbot.model.ChatResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    private final LLMService llmService;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${rag.service.url:http://localhost:8001}")
    private String ragServiceUrl;

    public KnowledgeService(LLMService llmService) {
        this.llmService = llmService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public ChatResponse generateResponse(String userMessage, String intent) {
        log.info("Knowledge response — intent={}", intent);

        // 1. RAG servisine sor
        try {
            String body = mapper.writeValueAsString(java.util.Map.of("query", userMessage));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(ragServiceUrl + "/query"))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode node = mapper.readTree(resp.body());
                boolean found = node.path("found").asBoolean(false);
                String answer = node.path("answer").asText("");
                if (found && !answer.isBlank()) {
                    log.info("RAG hit for query='{}'", userMessage);
                    ChatResponse chatResp = ChatResponse.completed(answer, "RAG_RESPONSE");

                    JsonNode locs = node.path("locations");
                    if (locs.isArray() && locs.size() > 0) {
                        java.util.List<java.util.Map<String, Object>> markers = new java.util.ArrayList<>();
                        for (JsonNode loc : locs) {
                            java.util.Map<String, Object> mk = new java.util.HashMap<>();
                            mk.put("lat", loc.path("lat").asDouble());
                            mk.put("lon", loc.path("lon").asDouble());
                            String label = loc.path("name").asText("");
                            String addr  = loc.path("address").asText("");
                            mk.put("label", label + (addr.isBlank() ? "" : " — " + addr));
                            markers.add(mk);
                        }
                        java.util.Map<String, Object> pinData = new java.util.HashMap<>();
                        pinData.put("type", "pins");
                        pinData.put("markers", markers);
                        pinData.put("segments", java.util.List.of());
                        chatResp.setRouteData(pinData);
                    }

                    return chatResp;
                }
            }
        } catch (Exception e) {
            log.warn("RAG service unavailable, falling back to LLM: {}", e.getMessage());
        }

        // 2. RAG bulamazsa LLM'e dön
        String answer = llmService.generateAnswer(userMessage);
        return ChatResponse.completed(answer, "KNOWLEDGE_RESPONSE");
    }

    public ChatResponse clarificationResponse() {
        return ChatResponse.completed(
                "Ne demek istediğinizi tam anlayamadım. Lütfen isteğinizi farklı bir şekilde ifade eder misiniz?",
                "CLARIFICATION"
        );
    }
}
