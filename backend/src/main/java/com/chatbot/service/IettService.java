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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class IettService {

    private static final Logger log = LoggerFactory.getLogger(IettService.class);
    private static final String SOAP_URL = "https://api.ibb.gov.tr/iett/FiloDurum/SeferGerceklesme.asmx";
    private static final Pattern JSON_RESULT = Pattern.compile("<GetHatOtoKonum_jsonResult>(.*?)</GetHatOtoKonum_jsonResult>", Pattern.DOTALL);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;

    public IettService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public String getBusLocation(String hatKodu) {
        if (hatKodu == null || hatKodu.isBlank()) {
            return "Hangi hat numarasını sorgulayalım? (Örnek: 34B, 500T, 28)";
        }

        String line = hatKodu.trim().toUpperCase();

        try {
            String soap = buildSoapEnvelope(line);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(SOAP_URL))
                    .POST(HttpRequest.BodyPublishers.ofString(soap))
                    .header("Content-Type", "text/xml; charset=utf-8")
                    .header("SOAPAction", "http://tempuri.org/GetHatOtoKonum_json")
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Matcher m = JSON_RESULT.matcher(response.body());
            if (!m.find()) return "Otobüs konum bilgisi alınamadı.";

            JsonNode buses = objectMapper.readTree(m.group(1));
            if (!buses.isArray() || buses.size() == 0) {
                return line + " hattında şu an aktif otobüs bulunamadı.";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(line).append(" hattında şu an ").append(buses.size()).append(" aktif otobüs:\n\n");

            int count = 0;
            for (JsonNode bus : buses) {
                if (count >= 5) { sb.append("... ve ").append(buses.size() - 5).append(" otobüs daha.\n"); break; }
                String kapiNo  = bus.path("kapino").asText("");
                String yon     = bus.path("yon").asText("");
                String zaman   = bus.path("son_konum_zamani").asText("").replaceFirst(".*? ", "");
                sb.append("• Araç ").append(kapiNo)
                  .append(" → ").append(yon)
                  .append(" (son konum: ").append(zaman).append(")\n");
                count++;
            }

            return sb.toString().trim();
        } catch (Exception e) {
            log.error("IETT otobüs konum hatası: {}", e.getMessage());
            return "Otobüs konum bilgisi şu an alınamıyor.";
        }
    }

    private String buildSoapEnvelope(String hatKodu) {
        return """
                <?xml version="1.0" encoding="utf-8"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body>
                    <GetHatOtoKonum_json xmlns="http://tempuri.org/">
                      <HatKodu>%s</HatKodu>
                    </GetHatOtoKonum_json>
                  </soap:Body>
                </soap:Envelope>""".formatted(hatKodu);
    }
}
