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
import java.util.Map;

/**
 * İBB Çözüm Merkezi entegrasyonu.
 * Dosya yükleme (SFTP) ve başvuru gönderme işlemlerini yönetir.
 */
@Service
public class IbbComplaintService {

    private static final Logger log = LoggerFactory.getLogger(IbbComplaintService.class);

    private static final String BASE_URL        = "https://api.ibb.gov.tr/cagri-merkezi-backend/api";
    private static final String UPLOAD_ENDPOINT = BASE_URL + "/Soap/SftpUpload";
    private static final String APPLY_ENDPOINT  = BASE_URL + "/Soap/newApplication";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient   httpClient;

    public IbbComplaintService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    // ── Model record for complaint data ──────────────────────────────────────

    public record ComplaintData(
            String firstName,
            String lastName,
            String tckn,
            String email,
            String mobilePhone,
            String birthDate,
            String applicationDescription,
            String applicationAddress,
            String district,
            List<String> filePaths,
            String mahalle,
            String caddeSokak,
            String binaNo,
            String daireNo
    ) {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Dosyayı İBB SFTP sunucusuna yükler.
     *
     * @param base64Content base64 kodlanmış dosya içeriği
     * @param fileName      dosya adı (ör. "foto.jpg")
     * @return Sunucudan dönen dosya yolları listesi; hata durumunda boş liste
     */
    public List<String> uploadFile(String base64Content, String fileName) {
        try {
            // Body: [{"fileName":"...","Base64Data":"..."}]
            String body = objectMapper.writeValueAsString(
                    List.of(Map.of("fileName", fileName, "Base64Data", base64Content))
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(UPLOAD_ENDPOINT))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("İBB SFTP yükleme başarısız — HTTP {}: {}", response.statusCode(), response.body());
                return List.of();
            }

            return parseFilePaths(response.body());

        } catch (Exception e) {
            log.warn("İBB SFTP yükleme hatası — dosya={}: {}", fileName, e.getMessage());
            return List.of();
        }
    }

    /**
     * İBB'ye şikayet başvurusu gönderir.
     *
     * @param data başvuru verileri
     * @return srNumber (takip numarası); hata durumunda null
     */
    public String submitComplaint(ComplaintData data) {
        try {
            // Telefon numarasını +90 formatına çevir
            String phone = formatPhone(data.mobilePhone());

            Map<String, Object> body = Map.ofEntries(
                    Map.entry("firstName",               nvl(data.firstName())),
                    Map.entry("lastName",                nvl(data.lastName())),
                    Map.entry("tckn",                    nvl(data.tckn())),
                    Map.entry("email",                   nvl(data.email())),
                    Map.entry("mobilePhone",             phone),
                    Map.entry("birthDate",               nvl(data.birthDate())),
                    Map.entry("applicationDescription",  nvl(data.applicationDescription())),
                    Map.entry("applicationAddress",      nvl(data.applicationAddress())),
                    Map.entry("district",                nvl(data.district())),
                    Map.entry("filePaths",               data.filePaths() != null ? data.filePaths() : List.of()),
                    Map.entry("isDescriptionApproved",   true),
                    Map.entry("mahalle",                 nvl(data.mahalle())),
                    Map.entry("caddeSokak",              nvl(data.caddeSokak())),
                    Map.entry("binaNo",                  nvl(data.binaNo())),
                    Map.entry("daireNo",                 nvl(data.daireNo()))
            );

            String jsonBody = objectMapper.writeValueAsString(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(APPLY_ENDPOINT))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("İBB başvuru gönderilemedi — HTTP {}: {}", response.statusCode(), response.body());
                return null;
            }

            JsonNode json = objectMapper.readTree(response.body());
            String srNumber = json.path("srNumber").asText(null);
            String ibbSecNo = json.path("ibbSecurityNumber").asText(null);
            log.info("İBB başvurusu oluşturuldu — srNumber={}, ibbSecurityNumber={}", srNumber, ibbSecNo);
            return srNumber;

        } catch (Exception e) {
            log.error("İBB başvuru hatası: {}", e.getMessage());
            return null;
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Sunucudan dönen dosya yollarını parse eder.
     * Yanıt düz dizi veya string olabilir.
     */
    @SuppressWarnings("unchecked")
    private List<String> parseFilePaths(String responseBody) {
        List<String> paths = new ArrayList<>();
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            if (node.isArray()) {
                node.forEach(n -> {
                    if (n.isTextual()) {
                        paths.add(n.asText());
                    } else {
                        // Nesne içinde path alanı olabilir
                        String p = n.path("path").asText(null);
                        if (p == null) p = n.path("filePath").asText(null);
                        if (p == null) p = n.path("fileName").asText(null);
                        if (p != null) paths.add(p);
                    }
                });
            } else if (node.isTextual()) {
                paths.add(node.asText());
            } else {
                // Yanıt tek nesne ise
                String p = node.path("path").asText(null);
                if (p == null) p = node.path("filePath").asText(null);
                if (p != null) paths.add(p);
            }
        } catch (Exception e) {
            // Ham metin yol olabilir
            String trimmed = responseBody.trim().replaceAll("[\"\\[\\]]", "");
            if (!trimmed.isBlank()) paths.add(trimmed);
        }
        return paths;
    }

    /**
     * Telefon numarasını +90XXXXXXXXXX formatına çevirir.
     */
    private String formatPhone(String raw) {
        if (raw == null) return "";
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.startsWith("90") && digits.length() == 12) {
            return "+" + digits;
        }
        if (digits.startsWith("0") && digits.length() == 11) {
            return "+90" + digits.substring(1);
        }
        if (digits.length() == 10) {
            return "+90" + digits;
        }
        // Zaten +90 ile başlıyorsa
        if (raw.startsWith("+90")) return raw;
        return "+90" + digits;
    }

    private String nvl(String value) {
        return value != null ? value : "";
    }
}
