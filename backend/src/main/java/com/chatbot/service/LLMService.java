package com.chatbot.service;

import com.chatbot.config.OllamaConfig;
import com.chatbot.model.LLMResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LLMService {

    private static final Logger log = LoggerFactory.getLogger(LLMService.class);
    private static final int MAX_RETRIES = 2;
    private static final Pattern JSON_BLOCK = Pattern.compile("\\{[\\s\\S]*?\\}");

    private final OllamaConfig ollamaConfig;
    private final OllamaHealthService healthService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;

    public LLMService(OllamaConfig ollamaConfig, OllamaHealthService healthService) {
        this.ollamaConfig = ollamaConfig;
        this.healthService = healthService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Sends the user message to the LLM for intent + entity extraction.
     * Returns a strict JSON: {"intent","entity","confidence"}.
     * Retries up to MAX_RETRIES times on parse failure.
     */
    public LLMResult extractIntent(String userMessage) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String raw = callOllama(buildIntentPrompt(userMessage), true);
                LLMResult result = parseIntentJson(raw);
                if (result != null && isValidIntent(result.getIntent())) {
                    log.info("Intent extracted (attempt {}): {}", attempt, result);
                    return result;
                }
                log.warn("Attempt {}/{} — bad LLM output: {}", attempt, MAX_RETRIES, raw);
            } catch (Exception e) {
                log.error("Attempt {}/{} — LLM call failed: {}", attempt, MAX_RETRIES, e.getMessage());
            }
        }
        return unknownResult();
    }

    /**
     * Generates a free-text answer for knowledge-mode queries.
     */
    public String generateAnswer(String userMessage) {
        try {
            return callOllama(buildAnswerPrompt(userMessage), false);
        } catch (Exception e) {
            log.error("Answer generation failed: {}", e.getMessage());
            return "Üzgünüm, isteğinizi şu anda işleyemedim. Lütfen tekrar deneyin.";
        }
    }

    /**
     * Şikayet metninin İBB Çözüm Merkezi için geçerli olup olmadığını kontrol eder.
     * Geçerliyse null döner. Geçersizse kullanıcıya gösterilecek Türkçe red mesajı döner.
     */
    public String validateComplaint(String description) {
        try {
            String raw = callOllama(buildValidationPrompt(description), true);
            return parseValidationResult(raw, description);
        } catch (Exception e) {
            log.warn("Complaint validation failed, skipping: {}", e.getMessage());
            return null; // Hata durumunda geçer say
        }
    }

    // ── Prompt builders ──────────────────────────────────────────────────────

    private String buildIntentPrompt(String userMessage) {
        return """
                Sen bir niyet sınıflandırma sistemisin.
                Tek görevin yalnızca bir JSON nesnesi döndürmek — açıklama, markdown veya fazladan metin olmadan.

                İzin verilen niyetler:
                - pay_bill        (fatura ödeme)
                - ibb_complaint   (İBB Çözüm Merkezi'ne şikayet veya talep)
                - metro_status    (metro hat durumu, duyuru, metro çalışıyor mu)
                - metro_ticket    (metro bilet fiyatı)
                - nearest_station (en yakın metro/metro istasyonu — sadece istasyon sormak için)
                - current_location (şu an neredeyim, konumum nerede, bulunduğum yer — adres sormak için)
                - traffic_info    (trafik durumu, yoğunluk)
                - metro_projects   (yapım aşamasındaki metro hatları, metro projeleri)
                - metro_activities (metro etkinlikleri, metro'da ne var)
                - metro_news       (metro haberleri, metro duyurusu)
                - metro_maps       (metro haritası, raylı sistem haritası)
                - bus_location     (otobüs nerede, hat konumu — entity: hat kodu örn. "34B")
                - route_to         (bir yere nasıl giderim, rota — entity: hedef yer adı)
                - route_question  (yol/rota sorusu)
                - general_question (genel soru)
                - unknown         (anlaşılamayan)

                Çıktı formatı (tam olarak bu şekilde):
                {"intent":"<niyet>","entity":<"string" veya null>,"confidence":<0.0-1.0>}

                Örnekler:
                Girdi: elektrik faturamı ödemek istiyorum
                Çıktı: {"intent":"pay_bill","entity":"electricity","confidence":0.93}

                Girdi: doğalgaz faturamı öde
                Çıktı: {"intent":"pay_bill","entity":"gas","confidence":0.95}

                Girdi: su faturası ödemek istiyorum
                Çıktı: {"intent":"pay_bill","entity":"water","confidence":0.92}

                Girdi: İSKİ borcum var mı
                Çıktı: {"intent":"pay_bill","entity":"water","confidence":0.90}

                Girdi: İBB'ye şikayet yapmak istiyorum
                Çıktı: {"intent":"ibb_complaint","entity":null,"confidence":0.95}

                Girdi: çözüm merkezine başvuru açmak istiyorum
                Çıktı: {"intent":"ibb_complaint","entity":null,"confidence":0.93}

                Girdi: sokak lambası bozuk şikayet etmek istiyorum
                Çıktı: {"intent":"ibb_complaint","entity":null,"confidence":0.90}

                Girdi: İBB'ye ihbar yapmak istiyorum
                Çıktı: {"intent":"ibb_complaint","entity":null,"confidence":0.92}

                Girdi: belediyeye şikayet açmak istiyorum
                Çıktı: {"intent":"ibb_complaint","entity":null,"confidence":0.88}

                Girdi: yol çökmesi bildirmek istiyorum
                Çıktı: {"intent":"ibb_complaint","entity":null,"confidence":0.87}

                Girdi: 153 hattına bağlanmak istiyorum
                Çıktı: {"intent":"ibb_complaint","entity":null,"confidence":0.85}

                Girdi: metro çalışıyor mu
                Çıktı: {"intent":"metro_status","entity":null,"confidence":0.95}

                Girdi: M2 hattında sorun var mı
                Çıktı: {"intent":"metro_status","entity":"M2","confidence":0.93}

                Girdi: metro bileti kaç para
                Çıktı: {"intent":"metro_ticket","entity":null,"confidence":0.96}

                Girdi: öğrenci metro ücreti ne kadar
                Çıktı: {"intent":"metro_ticket","entity":"student","confidence":0.94}

                Girdi: en yakın metro istasyonu nerede
                Çıktı: {"intent":"nearest_station","entity":null,"confidence":0.95}

                Girdi: yakınımda metro var mı
                Çıktı: {"intent":"nearest_station","entity":null,"confidence":0.92}

                Girdi: trafik nasıl
                Çıktı: {"intent":"traffic_info","entity":null,"confidence":0.95}

                Girdi: İstanbul trafiği yoğun mu
                Çıktı: {"intent":"traffic_info","entity":null,"confidence":0.93}

                Girdi: Hangi metro hatları yapılıyor?
                Çıktı: {"intent":"metro_projects","entity":null,"confidence":0.92}

                Girdi: Yeni metro hattı var mı?
                Çıktı: {"intent":"metro_projects","entity":null,"confidence":0.90}

                Girdi: Metro etkinlikleri neler?
                Çıktı: {"intent":"metro_activities","entity":null,"confidence":0.91}

                Girdi: Metroda etkinlik var mı?
                Çıktı: {"intent":"metro_activities","entity":null,"confidence":0.89}

                Girdi: Metro haberleri neler?
                Çıktı: {"intent":"metro_news","entity":null,"confidence":0.92}

                Girdi: Metro İstanbul'dan son haberler?
                Çıktı: {"intent":"metro_news","entity":null,"confidence":0.90}

                Girdi: metro haritası
                Çıktı: {"intent":"metro_maps","entity":null,"confidence":0.95}

                Girdi: İstanbul raylı sistem haritası
                Çıktı: {"intent":"metro_maps","entity":null,"confidence":0.93}

                Girdi: 34B otobüsü nerede?
                Çıktı: {"intent":"bus_location","entity":"34B","confidence":0.95}

                Girdi: 500T hattındaki otobüsler nerede?
                Çıktı: {"intent":"bus_location","entity":"500T","confidence":0.93}

                Girdi: 28 nolu otobüs nerede şu an?
                Çıktı: {"intent":"bus_location","entity":"28","confidence":0.91}

                Girdi: Eminönü'ne nasıl giderim?
                Çıktı: {"intent":"route_to","entity":"Eminönü","confidence":0.95}

                Girdi: Taksim'e gitmek istiyorum
                Çıktı: {"intent":"route_to","entity":"Taksim","confidence":0.93}

                Girdi: Kadıköy'e rota çiz
                Çıktı: {"intent":"route_to","entity":"Kadıköy","confidence":0.94}

                Girdi: şu an neredeyim?
                Çıktı: {"intent":"current_location","entity":null,"confidence":0.97}

                Girdi: neredeyim?
                Çıktı: {"intent":"current_location","entity":null,"confidence":0.96}

                Girdi: konumum nerede?
                Çıktı: {"intent":"current_location","entity":null,"confidence":0.95}

                Girdi: bulunduğum yer neresi?
                Çıktı: {"intent":"current_location","entity":null,"confidence":0.95}

                Girdi: Karaköy'den Eminönü'ne nasıl gidebilirim?
                Çıktı: {"intent":"route_question","entity":"transport","confidence":0.88}

                Girdi: bana fıkra anlat
                Çıktı: {"intent":"general_question","entity":null,"confidence":0.75}

                Girdi: asdfghjkl
                Çıktı: {"intent":"unknown","entity":null,"confidence":0.1}

                Şimdi sınıflandır:
                Girdi: %s
                Çıktı:""".formatted(userMessage);
    }

    private String buildValidationPrompt(String description) {
        return """
                Sen bir İBB Çözüm Merkezi başvuru denetim sistemisin.
                Verilen metni incele ve bu metnin gerçek, geçerli bir şikayet veya talep olup olmadığına karar ver.

                GEÇERSİZ örnekler (bunları reddet):
                - Saçma, anlamsız veya rastgele metinler ("asdf", "deneme", "123", "a")
                - Hakaret, küfür veya uygunsuz ifadeler
                - Sadece selamlama veya genel sorular ("merhaba", "nasılsın")
                - Boş veya çok kısa açıklamalar

                GEÇERLİ örnekler (bunları kabul et):
                - Altyapı sorunları (yol bozuk, su kesintisi, elektrik arızası)
                - Çevre sorunları (çöp toplanmıyor, gürültü, koku)
                - Ulaşım sorunları (otobüs gelmiyor, durak sorunları)
                - İmar/yapı sorunları
                - Belediye hizmetlerine dair her türlü makul şikayet veya talep

                Yalnızca şu JSON formatını döndür:
                {"valid": true} veya {"valid": false, "reason": "Kısa red sebebi Türkçe"}

                Şikayet metni: %s
                Yanıt:""".formatted(description);
    }

    private String parseValidationResult(String raw, String description) {
        try {
            // Önce doğrudan parse dene
            String cleaned = raw.replaceAll("(?s)```[a-z]*", "").replaceAll("```", "").trim();
            Matcher m = JSON_BLOCK.matcher(cleaned);
            while (m.find()) {
                try {
                    com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(m.group());
                    boolean valid = node.path("valid").asBoolean(true);
                    if (valid) return null; // geçerli
                    String reason = node.path("reason").asText(null);
                    return reason != null
                            ? "Şikayetiniz kabul edilemedi: " + reason + " Lütfen geçerli bir şikayet açıklaması girin."
                            : "Lütfen geçerli bir şikayet veya talep açıklaması girin.";
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.warn("Validation parse error: {}", e.getMessage());
        }
        return null; // parse edilemezse geçer say
    }

    private String buildAnswerPrompt(String userMessage) {
        return """
                Sen yardımcı bir Türkçe asistansın. Aşağıdaki soruyu Türkçe olarak açık ve kısa bir şekilde yanıtla.
                Cevabın her zaman Türkçe olmalı.

                Soru: %s

                Cevap:""".formatted(userMessage);
    }

    // ── Ollama HTTP call ──────────────────────────────────────────────────────

    private String callOllama(String prompt, boolean forceJson) throws Exception {
        String model = healthService.getResolvedModel();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("stream", false);
        if (forceJson) {
            body.put("format", "json");
        }

        String json = objectMapper.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ollamaConfig.getBaseUrl() + "/api/generate"))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(ollamaConfig.getTimeoutMs()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Ollama HTTP " + response.statusCode() + ": " + response.body());
        }

        return objectMapper.readTree(response.body()).path("response").asText().trim();
    }

    // ── JSON parsing with fallback ────────────────────────────────────────────

    private LLMResult parseIntentJson(String raw) {
        // 1. Direct parse
        try {
            return objectMapper.readValue(raw, LLMResult.class);
        } catch (Exception ignored) {}

        // 2. Strip markdown fences then parse
        String stripped = raw.replaceAll("(?s)```[a-z]*", "").replaceAll("```", "").trim();
        try {
            return objectMapper.readValue(stripped, LLMResult.class);
        } catch (Exception ignored) {}

        // 3. Regex extract first {...} block
        Matcher m = JSON_BLOCK.matcher(stripped);
        while (m.find()) {
            try {
                LLMResult r = objectMapper.readValue(m.group(), LLMResult.class);
                if (r.getIntent() != null) return r;
            } catch (Exception ignored) {}
        }

        return null;
    }

    private boolean isValidIntent(String intent) {
        if (intent == null) return false;
        return switch (intent) {
            case "pay_bill", "ibb_complaint",
                 "metro_status", "metro_ticket", "nearest_station", "traffic_info",
                 "metro_projects", "metro_activities", "metro_news",
                 "metro_maps", "bus_location", "route_to", "current_location",
                 "route_question", "general_question", "unknown" -> true;
            default -> false;
        };
    }

    private LLMResult unknownResult() {
        LLMResult r = new LLMResult();
        r.setIntent("unknown");
        r.setEntity(null);
        r.setConfidence(0.0);
        return r;
    }
}
