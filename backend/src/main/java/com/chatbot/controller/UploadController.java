package com.chatbot.controller;

import com.chatbot.service.IbbComplaintService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dosya yükleme endpoint'i.
 * Gelen multipart dosyasını base64'e çevirir ve İBB SFTP'ye yükler.
 */
@RestController
@RequestMapping("/api")
public class UploadController {

    private static final Logger log = LoggerFactory.getLogger(UploadController.class);

    /** Maksimum dosya boyutu: 10MB */
    private static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;

    /** İzin verilen dosya uzantıları */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpeg", "jpg", "png", "pdf");

    /** İzin verilen MIME tipleri */
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "application/pdf"
    );

    private final IbbComplaintService ibbComplaintService;

    public UploadController(IbbComplaintService ibbComplaintService) {
        this.ibbComplaintService = ibbComplaintService;
    }

    /**
     * POST /api/upload
     * Multipart dosya alır, doğrular, İBB SFTP'ye yükler ve dosya yolunu döner.
     *
     * @param file yüklenecek dosya
     * @return {"filePath": "..."} veya hata mesajı
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {

        // 1. Dosya boş mu?
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Dosya boş olamaz."));
        }

        // 2. Dosya boyutu kontrolü
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "Dosya boyutu 10MB sınırını aşıyor. Lütfen daha küçük bir dosya seçin.")
            );
        }

        // 3. Uzantı kontrolü
        String originalName = file.getOriginalFilename();
        String extension    = getExtension(originalName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "Yalnızca şu dosya türleri desteklenir: jpeg, jpg, png, pdf.")
            );
        }

        // 4. Content-type kontrolü
        String contentType = file.getContentType();
        if (contentType != null && !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "Geçersiz dosya türü: " + contentType)
            );
        }

        // 5. Base64 dönüşümü
        String base64Content;
        try {
            byte[] bytes = file.getBytes();
            base64Content = Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            log.error("Dosya okunurken hata: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Dosya okunamadı."));
        }

        // 6. İBB SFTP'ye yükle
        String safeFileName = sanitizeFileName(originalName);
        log.info("Dosya yükleniyor: {}, boyut: {} bytes", safeFileName, file.getSize());

        List<String> paths = ibbComplaintService.uploadFile(base64Content, safeFileName);

        if (paths.isEmpty()) {
            log.warn("İBB SFTP yüklemesi başarısız — dosya: {}", safeFileName);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "Dosya sunucuya yüklenemedi. Lütfen tekrar deneyin."));
        }

        String filePath = paths.get(0);
        log.info("Dosya yüklendi: {} → {}", safeFileName, filePath);

        return ResponseEntity.ok(Map.of(
                "filePath",  filePath,
                "fileName",  safeFileName,
                "fileSize",  file.getSize()
        ));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String getExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) return "";
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }

    /**
     * Dosya adından zararlı karakterleri temizler.
     */
    private String sanitizeFileName(String fileName) {
        if (fileName == null) return "upload";
        // Yalnızca alfanümerik, nokta, tire, alt çizgiye izin ver
        String safe = fileName.replaceAll("[^a-zA-Z0-9.\\-_]", "_");
        // Uzunluğu sınırla
        if (safe.length() > 100) {
            String ext = getExtension(safe);
            safe = safe.substring(0, 95) + "." + ext;
        }
        return safe;
    }
}
