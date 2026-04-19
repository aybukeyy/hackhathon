package com.chatbot.service;

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
public class TrafficService {

    private static final Logger log = LoggerFactory.getLogger(TrafficService.class);
    private static final String TRAFFIC_URL =
            "https://api.ibb.gov.tr/tkmservices/api/TrafficData/v1/TrafficIndexHistory/1/5M";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;

    public TrafficService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ── Anlık trafik bilgisi (sohbet için) ───────────────────────────────────

    public String getTrafficInfo() {
        try {
            List<Integer> data = fetchIndexHistory();
            if (data.isEmpty()) return "Trafik verisi şu an alınamıyor.";

            int current = data.get(0);
            String desc = indexToDescription(current);

            return String.format(
                "İstanbul anlık trafik durumu:\n\n🚦 Yoğunluk indeksi: %d/10\nDurum: %s\n\n" +
                "TKM verilerine göre %s. Alternatif güzergah veya metro kullanmanızı öneririz.",
                current, desc, desc.toLowerCase()
            );
        } catch (Exception e) {
            log.error("Trafik bilgisi alınamadı: {}", e.getMessage());
            return "Trafik bilgisi şu an alınamıyor. tkm.istanbul adresini kontrol edebilirsiniz.";
        }
    }

    // ── Frontend trafik kartı için ham veri ──────────────────────────────────

    public List<Integer> fetchIndexHistory() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(TRAFFIC_URL))
                .GET()
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode arr = objectMapper.readTree(res.body());

        List<Integer> values = new ArrayList<>();
        if (arr.isArray()) {
            // En eski → en yeni sıraya çevir (API ters sırayla veriyor), son 7 değer al
            List<Integer> all = new ArrayList<>();
            for (JsonNode node : arr) all.add(node.path("TrafficIndex").asInt(0));
            // Ters çevir: en eski başta
            for (int i = all.size() - 1; i >= 0; i--) values.add(all.get(i));
            // Son 7'yi al
            if (values.size() > 7) values = values.subList(values.size() - 7, values.size());
        }
        return values;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String indexToDescription(int index) {
        if (index <= 2)  return "Trafik akıcı";
        if (index <= 4)  return "Trafik hafif yoğun";
        if (index <= 6)  return "Trafik yoğun";
        if (index <= 8)  return "Trafik çok yoğun";
        return "Trafik son derece yoğun";
    }
}
