package com.chatbot.controller;

import com.chatbot.model.ChatRequest;
import com.chatbot.model.ChatResponse;
import com.chatbot.service.OrchestratorService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final OrchestratorService orchestratorService;

    public ChatController(OrchestratorService orchestratorService) {
        this.orchestratorService = orchestratorService;
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        ChatResponse response = orchestratorService.process(request);
        return ResponseEntity.ok(response);
    }
}
