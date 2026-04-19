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
public class IskiService {

    private static final Logger log = LoggerFactory.getLogger(IskiService.class);
    private static final String API_URL =
            "https://esubeapi.iski.gov.tr/EsubeAPI/tahsilat/uyeliksizTCyeBagliBorclar";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public record BillInfo(String aboneNo, String tutar, String donem, String durum) {}

    public record IskiResult(boolean success, String errorMessage, List<BillInfo> bills) {}

    /**
     * Queries outstanding İSKİ water bills for a given TC identity number.
     * Endpoint: POST https://esubeapi.iski.gov.tr/EsubeAPI/tahsilat/uyeliksizTCyeBagliBorclar
     * No authentication required for this public endpoint.
     */
    public IskiResult queryByTcNo(String tcNo) {
        try {
            String body = objectMapper.writeValueAsString(java.util.Map.of("tcNo", tcNo));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Origin", "https://esube.iski.gov.tr")
                    .header("Referer", "https://esube.iski.gov.tr/")
                    .header("lang", "TR")
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("İSKİ API status: {}", response.statusCode());

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode status = root.path("status");
            JsonNode data   = root.path("data");

            String description = status.path("description").asText("");

            // 400 = TC validation error from İSKİ
            if (response.statusCode() == 400) {
                return new IskiResult(false, description, List.of());
            }

            // Parse bill list
            List<BillInfo> bills = new ArrayList<>();
            if (data.isArray()) {
                for (JsonNode item : data) {
                    String aboneNo = firstNonEmpty(item,
                            "aboneNo", "abone_no", "abonelik_no", "contractNo");
                    String tutar = firstNonEmpty(item,
                            "borcTutar", "tutar", "totalAmount", "amount", "toplamBorc");
                    String donem = firstNonEmpty(item,
                            "donem", "faturaDonemi", "period", "faturaNo");
                    String durum = firstNonEmpty(item,
                            "durum", "status", "odenmeDurumu");
                    bills.add(new BillInfo(aboneNo, tutar, donem, durum));
                }
            }

            if (bills.isEmpty() && (description.isBlank() || description.equalsIgnoreCase("OK"))) {
                return new IskiResult(true, null, List.of());
            }

            return new IskiResult(true, null, bills);

        } catch (Exception e) {
            log.error("İSKİ API error: {}", e.getMessage());
            return new IskiResult(false, "İSKİ servisine bağlanılamadı.", List.of());
        }
    }

    private String firstNonEmpty(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode val = node.path(key);
            if (!val.isMissingNode() && !val.isNull() && !val.asText().isBlank()) {
                return val.asText();
            }
        }
        return "-";
    }
}
