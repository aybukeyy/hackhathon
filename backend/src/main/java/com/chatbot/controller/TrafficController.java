package com.chatbot.controller;

import com.chatbot.service.TrafficService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TrafficController {

    private final TrafficService trafficService;

    public TrafficController(TrafficService trafficService) {
        this.trafficService = trafficService;
    }

    @GetMapping("/traffic")
    public ResponseEntity<Map<String, Object>> getTraffic() {
        try {
            List<Integer> history = trafficService.fetchIndexHistory();
            int current = history.isEmpty() ? 0 : history.get(history.size() - 1);
            return ResponseEntity.ok(Map.of("history", history, "current", current));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("history", List.of(), "current", 0));
        }
    }
}
