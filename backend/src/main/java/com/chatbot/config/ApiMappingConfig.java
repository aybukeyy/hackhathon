package com.chatbot.config;

import org.springframework.context.annotation.Configuration;
import java.util.Map;

@Configuration
public class ApiMappingConfig {

    private static final Map<String, String> PROVIDERS = Map.of(
            "electricity", "BEDAŞ",
            "gas",         "İGDAŞ",
            "water",       "İSKİ"
    );

    public String getProvider(String entity) {
        if (entity == null || entity.isBlank()) return "Unknown Provider";
        return PROVIDERS.getOrDefault(entity.toLowerCase(), "Unknown Provider");
    }

    public Map<String, String> getAll() {
        return PROVIDERS;
    }
}
