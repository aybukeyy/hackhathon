package com.chatbot.service;

import com.chatbot.model.ConversationState;
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
import java.util.*;

@Service
public class MetroService {

    private static final Logger log = LoggerFactory.getLogger(MetroService.class);
    private static final String BASE = "https://api.ibb.gov.tr/MetroIstanbul/api/MetroMobile/V2";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;

    // İstasyon listesini önbelleğe al (büyük liste, sık değişmez)
    private List<JsonNode> cachedStations = null;

    public MetroService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ── Metro hizmet durumu + duyurular ──────────────────────────────────────

    public String getServiceStatus() {
        try {
            JsonNode statuses = get("/GetServiceStatuses");
            JsonNode announcements = get("/GetAnnouncements/tr");

            StringBuilder sb = new StringBuilder();

            // Hizmet durumu
            JsonNode data = statuses.path("Data");
            if (data.isArray() && data.size() > 0) {
                sb.append("Metro hat hizmet durumları:\n");
                for (JsonNode item : data) {
                    String name    = item.path("LineName").asText("");
                    String content = item.path("LineShortDescription").asText(
                                     item.path("LineContent").asText(""));
                    if (!name.isBlank()) {
                        sb.append("• ").append(name);
                        if (!content.isBlank()) sb.append(": ").append(stripHtml(content));
                        sb.append("\n");
                    }
                }
            } else {
                sb.append("Tüm metro hatları normal hizmette.\n");
            }

            // Güncel duyurular (ilk 3)
            JsonNode ann = announcements.path("Data");
            if (ann.isArray() && ann.size() > 0) {
                sb.append("\nGüncel duyurular:\n");
                int count = 0;
                for (JsonNode item : ann) {
                    if (count >= 3) break;
                    String title = item.path("Title").asText("");
                    if (!title.isBlank()) {
                        sb.append("• ").append(title).append("\n");
                        count++;
                    }
                }
            }

            return sb.toString().trim();
        } catch (Exception e) {
            log.error("Metro hizmet durumu alınamadı: {}", e.getMessage());
            return "Metro hizmet durumu şu an alınamıyor. Lütfen metro.istanbul sitesini kontrol edin.";
        }
    }

    // ── Bilet fiyatları ───────────────────────────────────────────────────────

    public String getTicketPrices() {
        try {
            JsonNode data = get("/GetTicketPrice/tr").path("Data");
            StringBuilder sb = new StringBuilder("Metro İstanbul güncel bilet fiyatları:\n\n");

            for (JsonNode group : data) {
                String type = group.path("Type").asText("");
                sb.append("📋 ").append(type).append("\n");
                for (JsonNode ticket : group.path("TicketPrices")) {
                    String name  = ticket.path("Name").asText("");
                    String price = ticket.path("Price").asText("");
                    sb.append("  • ").append(name).append(": ").append(price).append("\n");
                }
                sb.append("\n");
            }

            return sb.toString().trim();
        } catch (Exception e) {
            log.error("Bilet fiyatları alınamadı: {}", e.getMessage());
            return "Bilet fiyatları şu an alınamıyor. metro.istanbul adresinden güncel fiyatlara ulaşabilirsiniz.";
        }
    }

    // ── En yakın istasyon ─────────────────────────────────────────────────────

    public String getNearestStation(ConversationState state) {
        Object latObj = state.getData().get("lat");
        Object lngObj = state.getData().get("lng");

        if (latObj == null || lngObj == null) {
            return "Konumunuzu paylaşmadığınız için en yakın istasyonu bulamıyorum. Lütfen tarayıcı konum iznini etkinleştirin.";
        }

        double userLat = ((Number) latObj).doubleValue();
        double userLng = ((Number) lngObj).doubleValue();

        try {
            List<JsonNode> stations = getStations();
            JsonNode nearest = null;
            double minDist = Double.MAX_VALUE;

            for (JsonNode s : stations) {
                JsonNode detail = s.path("DetailInfo");
                String latStr = detail.path("Latitude").asText("");
                String lngStr = detail.path("Longitude").asText("");
                if (latStr.isBlank() || lngStr.isBlank()) continue;
                try {
                    double lat = Double.parseDouble(latStr);
                    double lng = Double.parseDouble(lngStr);
                    double dist = distance(userLat, userLng, lat, lng);
                    if (dist < minDist) { minDist = dist; nearest = s; }
                } catch (NumberFormatException ignored) {}
            }

            if (nearest == null) return "En yakın istasyon bulunamadı.";

            String name    = nearest.path("Description").asText(nearest.path("Name").asText(""));
            String line    = nearest.path("LineName").asText("");
            int    distM   = (int) (minDist * 1000);
            JsonNode detail = nearest.path("DetailInfo");
            int escalators = detail.path("Escolator").asInt(0);
            int lifts      = detail.path("Lift").asInt(0);

            return String.format(
                "En yakın metro istasyonu: %s (%s hattı)\nUzaklık: yaklaşık %d metre\n%s%s",
                name, line, distM,
                escalators > 0 ? "Yürüyen merdiven: " + escalators + " adet\n" : "",
                lifts > 0      ? "Asansör: " + lifts + " adet" : ""
            );
        } catch (Exception e) {
            log.error("En yakın istasyon hesaplanamadı: {}", e.getMessage());
            return "İstasyon bilgisi şu an alınamıyor.";
        }
    }

    // ── Haritalar ─────────────────────────────────────────────────────────────

    public String getMaps() {
        try {
            JsonNode data = get("/GetMaps").path("Data");
            if (!data.isArray() || data.size() == 0) return "Metro harita bilgisi bulunamadı.";
            StringBuilder sb = new StringBuilder("Metro İstanbul haritaları:\n\n");
            for (JsonNode item : data) {
                if (!item.path("IsActive").asBoolean(true)) continue;
                String title    = item.path("Title").asText("");
                String imageUrl = item.path("ImageURL").asText("");
                if (!title.isBlank() && isTurkish(title)) {
                    sb.append("• ").append(title).append("\n");
                    if (!imageUrl.isBlank()) sb.append("  ").append(imageUrl).append("\n");
                    sb.append("\n");
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.error("Haritalar alınamadı: {}", e.getMessage());
            return "Harita bilgisi şu an alınamıyor.";
        }
    }

    // ── Hat projeleri ─────────────────────────────────────────────────────────

    public String getLineProjects() {
        try {
            JsonNode data = get("/GetLineProjects").path("Data");
            if (!data.isArray() || data.size() == 0) return "Metro hattı proje bilgisi bulunamadı.";
            StringBuilder sb = new StringBuilder("Metro İstanbul devam eden hat projeleri:\n\n");
            for (JsonNode item : data) {
                String name = item.path("Name").asText("");
                if (name.isBlank() || !isTurkish(name)) continue;
                String desc = stripHtml(item.path("Description").asText(""));
                sb.append("• ").append(name).append("\n");
                if (!desc.isBlank() && desc.length() > 10) {
                    String summary = desc.length() > 200 ? desc.substring(0, 200) + "..." : desc;
                    sb.append("  ").append(summary).append("\n");
                }
                sb.append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.error("Hat projeleri alınamadı: {}", e.getMessage());
            return "Metro hat projeleri şu an alınamıyor. metro.istanbul adresini ziyaret edebilirsiniz.";
        }
    }

    // ── Etkinlikler ───────────────────────────────────────────────────────────

    public String getActivities() {
        try {
            JsonNode data = get("/GetActivities").path("Data");
            if (!data.isArray() || data.size() == 0) return "Şu an aktif etkinlik bulunmuyor.";
            StringBuilder sb = new StringBuilder("Metro İstanbul etkinlikleri:\n\n");
            int count = 0;
            for (JsonNode item : data) {
                if (count >= 5) break;
                String title = item.path("Title").asText("");
                String content = stripHtml(item.path("Content").asText(""));
                if (!title.isBlank()) {
                    sb.append("• ").append(title).append("\n");
                    if (!content.isBlank() && content.length() > 10) {
                        String summary = content.length() > 150 ? content.substring(0, 150) + "..." : content;
                        sb.append("  ").append(summary).append("\n");
                    }
                    sb.append("\n");
                    count++;
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.error("Etkinlikler alınamadı: {}", e.getMessage());
            return "Etkinlik bilgisi şu an alınamıyor.";
        }
    }

    // ── Haberler ──────────────────────────────────────────────────────────────

    public String getNews() {
        try {
            JsonNode data = get("/GetNews/tr").path("Data");
            if (!data.isArray() || data.size() == 0) return "Şu an haber bulunmuyor.";
            StringBuilder sb = new StringBuilder("Metro İstanbul son haberleri:\n\n");
            int count = 0;
            for (JsonNode item : data) {
                if (count >= 5) break;
                String title = item.path("Title").asText("");
                String content = stripHtml(item.path("Content").asText(""));
                if (!title.isBlank()) {
                    sb.append("• ").append(title).append("\n");
                    if (!content.isBlank() && content.length() > 10) {
                        String summary = content.length() > 150 ? content.substring(0, 150) + "..." : content;
                        sb.append("  ").append(summary).append("\n");
                    }
                    sb.append("\n");
                    count++;
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.error("Haberler alınamadı: {}", e.getMessage());
            return "Haber bilgisi şu an alınamıyor.";
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<JsonNode> getStations() throws Exception {
        if (cachedStations != null) return cachedStations;
        JsonNode data = get("/GetStations").path("Data");
        List<JsonNode> list = new ArrayList<>();
        if (data.isArray()) data.forEach(list::add);
        cachedStations = list;
        log.info("İstasyon listesi önbelleğe alındı: {} istasyon", list.size());
        return list;
    }

    private JsonNode get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE + path))
                .GET()
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        return objectMapper.readTree(res.body());
    }

    // Haversine mesafe (km)
    private double distance(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat/2) * Math.sin(dLat/2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLng/2) * Math.sin(dLng/2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    }

    private String stripHtml(String html) {
        if (html == null) return "";
        return html
            .replaceAll("<[^>]+>", "")
            // HTML named entities → Türkçe/Latin karakter
            .replace("&ccedil;", "ç").replace("&Ccedil;", "Ç")
            .replace("&ouml;",   "ö").replace("&Ouml;",   "Ö")
            .replace("&uuml;",   "ü").replace("&Uuml;",   "Ü")
            .replace("&iuml;",   "ï").replace("&Iuml;",   "Ï")
            .replace("&szlig;",  "ß")
            .replace("&amp;",    "&")
            .replace("&lt;",     "<")
            .replace("&gt;",     ">")
            .replace("&quot;",   "\"")
            .replace("&apos;",   "'")
            .replace("&nbsp;",   " ")
            .replaceAll("&#[0-9]+;", "")
            .replaceAll("&[a-zA-Z]+;", "")
            .replaceAll("\\s{2,}", " ")
            .trim();
    }

    // Türkçe karakter içeriyorsa ve İngilizce suffix ile bitmiyorsa Türkçe metindir
    private boolean isTurkish(String text) {
        boolean hasTurkishChar = text.chars().anyMatch(c ->
            c == 'ı' || c == 'İ' || c == 'ğ' || c == 'Ğ' ||
            c == 'ş' || c == 'Ş' || c == 'ç' || c == 'Ç' ||
            c == 'ö' || c == 'Ö' || c == 'ü' || c == 'Ü'
        );
        String lower = text.toLowerCase();
        boolean endsWithEnglish = lower.endsWith(" line") || lower.endsWith(" extension")
            || lower.endsWith(" phase") || lower.endsWith(" funicular line")
            || lower.endsWith(" tram line") || lower.endsWith(" metro line");
        return hasTurkishChar && !endsWithEnglish;
    }
}
