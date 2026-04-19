package com.chatbot.controller;

import com.chatbot.service.OllamaHealthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/health")
public class HealthController {

    private final OllamaHealthService ollamaHealthService;

    public HealthController(OllamaHealthService ollamaHealthService) {
        this.ollamaHealthService = ollamaHealthService;
    }

    @GetMapping("/ollama")
    public ResponseEntity<Map<String, Object>> ollamaHealth() {
        boolean reachable = ollamaHealthService.isOllamaReachable();
        List<String> models = reachable ? ollamaHealthService.fetchAvailableModels() : List.of();
        String activeModel = ollamaHealthService.getResolvedModel();

        return ResponseEntity.ok(Map.of(
                "status",          reachable ? "UP" : "DOWN",
                "reachable",       reachable,
                "activeModel",     activeModel != null ? activeModel : "none",
                "availableModels", models
        ));
    }
}
