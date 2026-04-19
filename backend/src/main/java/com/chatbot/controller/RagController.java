package com.chatbot.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Placeholder RAG endpoint.
 * Replace with real vector-search integration when RAG is implemented.
 */
@RestController
@RequestMapping("/rag")
public class RagController {

    @PostMapping("/query")
    public ResponseEntity<Map<String, Object>> query(@RequestBody Map<String, String> body) {
        String query = body.getOrDefault("query", "");
        return ResponseEntity.ok(Map.of(
                "query",   query,
                "result",  "RAG is not yet implemented. This is a placeholder response.",
                "sources", List.of()
        ));
    }
}
