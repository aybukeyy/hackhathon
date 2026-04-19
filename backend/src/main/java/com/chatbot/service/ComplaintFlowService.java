package com.chatbot.service;

import com.chatbot.model.ChatResponse;
import com.chatbot.model.ConversationState;
import com.chatbot.model.FlowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * İBB Çözüm Merkezi şikayet akışını yönetir.
 *
 * Adımlar:
 *  1. ASK_COMPLAINT_DESCRIPTION  – Şikayet açıklamasını al
 *  2. ASK_COMPLAINT_PERSONAL     – TC, ad, soyad, e-posta, tel, doğum tarihi al
 *  3. ASK_COMPLAINT_PHOTO        – Opsiyonel fotoğraf yüklemesi (veya "hayır" ile geç)
 *  4. CONFIRM_COMPLAINT          – Özeti göster, onay bekle, başvuruyu gönder
 */
@Service
public class ComplaintFlowService {

    private static final Logger log = LoggerFactory.getLogger(ComplaintFlowService.class);

    private static final Set<String> SKIP_WORDS = Set.of(
            "hayır", "hayir", "geç", "gec", "skip", "atla", "yok", "istemiyorum", "istemıyorum", "no"
    );

    private static final Set<String> CONFIRM_WORDS = Set.of(
            "evet", "yes", "onay", "onayla", "gönder", "gonder", "tamam", "ok", "e"
    );

    private static final Set<String> CANCEL_WORDS = Set.of(
            "iptal", "vazgeç", "vazgec", "cancel", "dur", "bırak", "birak"
    );

    // Kişisel bilgi parse için regex kalıpları
    private static final Pattern TC_PATTERN     = Pattern.compile("(?i)(?:TC[:\\s]*)(\\d{11})");
    private static final Pattern AD_PATTERN     = Pattern.compile("(?i)(?:Ad[:\\s]+)([\\p{L}]+)");
    private static final Pattern SOYAD_PATTERN  = Pattern.compile("(?i)(?:Soyad[:\\s]+)([\\p{L}]+)");
    private static final Pattern EMAIL_PATTERN  = Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");
    private static final Pattern TEL_PATTERN    = Pattern.compile("(?i)(?:Tel[:\\s]*)?([0-9]{10,11}|\\+90[0-9]{10})");
    private static final Pattern DOGUM_PATTERN  = Pattern.compile("(?i)(?:Do[gğ]um[:\\s]*)?(\\d{2}[./\\-]\\d{2}[./\\-]\\d{4})");
    private static final Pattern FOTO_PATTERN   = Pattern.compile("^FOTO_YUKLENDI:(.+)$");

    private final ConversationStateService stateService;
    private final IbbComplaintService      ibbService;
    private final LLMService               llmService;

    public ComplaintFlowService(ConversationStateService stateService,
                                IbbComplaintService ibbService,
                                LLMService llmService) {
        this.stateService = stateService;
        this.ibbService   = ibbService;
        this.llmService   = llmService;
    }

    // ── Entry points ──────────────────────────────────────────────────────────

    /**
     * Yeni şikayet akışını başlatır; oturuma state oluşturur.
     */
    public ChatResponse startComplaintFlow(String sessionId) {
        ConversationState state = new ConversationState();
        state.setSessionId(sessionId);
        state.setIntent("ibb_complaint");
        state.setEntity(null);
        state.setStep(FlowStep.ASK_COMPLAINT_DESCRIPTION);
        stateService.save(state);

        log.info("Şikayet akışı başlatıldı — session={}", sessionId);

        return ChatResponse.waitingInput(
                "İBB Çözüm Merkezi başvurusu başlatıyorum. Lütfen şikayetinizi veya talebinizi ayrıntılı biçimde açıklayın:",
                "ASK_COMPLAINT_DESCRIPTION"
        );
    }

    /**
     * Mevcut akışı sürdürür. Gelen mesaja göre adımı işler.
     */
    public ChatResponse continueComplaintFlow(ConversationState state, String userMessage) {
        // İptal kontrolü
        if (isCancelMessage(userMessage)) {
            stateService.clearState(state.getSessionId());
            return ChatResponse.completed(
                    "Şikayet başvurusu iptal edildi. Başka nasıl yardımcı olabilirim?",
                    "CANCELLED"
            );
        }

        return switch (state.getStep()) {
            case ASK_COMPLAINT_DESCRIPTION -> handleDescription(state, userMessage);
            case ASK_COMPLAINT_PERSONAL    -> handlePersonal(state, userMessage);
            case ASK_COMPLAINT_PHOTO       -> handlePhoto(state, userMessage);
            case CONFIRM_COMPLAINT         -> handleConfirm(state, userMessage);
            default -> {
                stateService.clearState(state.getSessionId());
                yield ChatResponse.completed("Bir hata oluştu, lütfen tekrar başlayın.", "ERROR");
            }
        };
    }

    // ── Step handlers ─────────────────────────────────────────────────────────

    private ChatResponse handleDescription(ConversationState state, String userMessage) {
        String description = userMessage.trim();
        if (description.length() < 10) {
            return ChatResponse.waitingInput(
                    "Lütfen şikayetinizi daha ayrıntılı açıklayın (en az 10 karakter):",
                    "ASK_COMPLAINT_DESCRIPTION"
            );
        }

        // LLM ile geçerlilik kontrolü
        String validationError = llmService.validateComplaint(description);
        if (validationError != null) {
            log.info("Şikayet geçersiz bulundu: {}", description);
            return ChatResponse.waitingInput(validationError, "ASK_COMPLAINT_DESCRIPTION");
        }

        state.getData().put("description", description);
        state.setStep(FlowStep.ASK_COMPLAINT_PERSONAL);
        stateService.save(state);

        return ChatResponse.waitingInput(
                "Teşekkürler. Şimdi kişisel bilgilerinizi girin. Lütfen aşağıdaki formatta yazın:\n\n" +
                "TC: 12345678901, Ad: Ahmet, Soyad: Yılmaz, Email: ahmet@ornek.com, " +
                "Tel: 5551234567, Doğum: 01/01/1990\n\n" +
                "(Adres bilgileri için ilçe, mahalle, cadde/sokak, bina no ve daire no da ekleyebilirsiniz. Örnek: " +
                "İlçe: Kadıköy, Mahalle: Moda, Cadde: Bahariye Caddesi, Bina: 12, Daire: 3)",
                "ASK_COMPLAINT_PERSONAL"
        );
    }

    private ChatResponse handlePersonal(ConversationState state, String userMessage) {
        String input = userMessage.trim();

        // Zorunlu alanları parse et
        String tc     = extractGroup(TC_PATTERN,    input, 1);
        String ad     = extractGroup(AD_PATTERN,    input, 1);
        String soyad  = extractGroup(SOYAD_PATTERN, input, 1);
        String email  = extractFirstMatch(EMAIL_PATTERN, input);
        String tel    = parsePhone(input);
        String dogum  = parseBirthDate(input);

        // Adres alanları (opsiyonel)
        String ilce    = extractLabeledValue(input, "İlçe|Ilce|ilçe|ilce|District");
        String mahalle = extractLabeledValue(input, "Mahalle|mahalle");
        String cadde   = extractLabeledValue(input, "Cadde|cadde|Sokak|sokak|CaddeSokak");
        String binaNo  = extractLabeledValue(input, "Bina|bina|BinaNo|bina no");
        String daireNo = extractLabeledValue(input, "Daire|daire|DaireNo|daire no");

        // Zorunlu alan kontrolü
        List<String> eksik = new ArrayList<>();
        if (tc == null || tc.length() != 11)  eksik.add("TC kimlik numarası (11 hane)");
        if (ad == null)                         eksik.add("Ad");
        if (soyad == null)                      eksik.add("Soyad");
        if (email == null)                      eksik.add("E-posta");
        if (tel == null)                        eksik.add("Telefon numarası");
        if (dogum == null)                      eksik.add("Doğum tarihi (GG/AA/YYYY)");

        if (!eksik.isEmpty()) {
            return ChatResponse.waitingInput(
                    "Aşağıdaki bilgiler eksik veya hatalı:\n• " + String.join("\n• ", eksik) +
                    "\n\nLütfen tüm bilgileri şu formatta tekrar girin:\n" +
                    "TC: 12345678901, Ad: Ahmet, Soyad: Yılmaz, Email: ahmet@ornek.com, " +
                    "Tel: 5551234567, Doğum: 01/01/1990",
                    "ASK_COMPLAINT_PERSONAL"
            );
        }

        // State'e kaydet
        state.getData().put("tc",      tc);
        state.getData().put("ad",      ad);
        state.getData().put("soyad",   soyad);
        state.getData().put("email",   email);
        state.getData().put("tel",     tel);
        state.getData().put("dogum",   dogum);
        state.getData().put("ilce",    ilce    != null ? ilce    : "");
        state.getData().put("mahalle", mahalle != null ? mahalle : "");
        state.getData().put("cadde",   cadde   != null ? cadde   : "");
        state.getData().put("binaNo",  binaNo  != null ? binaNo  : "");
        state.getData().put("daireNo", daireNo != null ? daireNo : "");

        state.setStep(FlowStep.ASK_COMPLAINT_PHOTO);
        stateService.save(state);

        return ChatResponse.waitingInput(
                "Bilgileriniz kaydedildi. Şikayetinizle ilgili fotoğraf veya belge eklemek ister misiniz?\n\n" +
                "📎 Dosya yüklemek için ataç butonunu kullanın (jpeg, jpg, png, pdf — maks. 10MB, en fazla 5 dosya).\n" +
                "Dosya eklemek istemiyorsanız \"hayır\" yazın.",
                "ASK_COMPLAINT_PHOTO"
        );
    }

    @SuppressWarnings("unchecked")
    private ChatResponse handlePhoto(ConversationState state, String userMessage) {
        String msg = userMessage.trim();

        // Fotoğraf yüklendi mi?
        Matcher fotoMatcher = FOTO_PATTERN.matcher(msg);
        if (fotoMatcher.matches()) {
            String filePath = fotoMatcher.group(1).trim();
            List<String> paths = (List<String>) state.getData().computeIfAbsent("filePaths", k -> new ArrayList<String>());
            paths.add(filePath);
            state.getData().put("filePaths", paths);
            stateService.save(state);

            int count = paths.size();
            if (count >= 5) {
                // Maksimum dosyaya ulaşıldı — özete geç
                return proceedToConfirm(state);
            }

            return ChatResponse.waitingInput(
                    String.format("Dosya eklendi (%d/5). Başka dosya eklemek ister misiniz? " +
                                  "Ek yoksa \"hayır\" yazın.", count),
                    "ASK_COMPLAINT_PHOTO"
            );
        }

        // Kullanıcı atlamak istiyor
        if (isSkipMessage(msg)) {
            return proceedToConfirm(state);
        }

        // Tanımsız yanıt
        return ChatResponse.waitingInput(
                "Lütfen 📎 ataç butonuyla dosya yükleyin veya \"hayır\" yazarak devam edin.",
                "ASK_COMPLAINT_PHOTO"
        );
    }

    @SuppressWarnings("unchecked")
    private ChatResponse handleConfirm(ConversationState state, String userMessage) {
        if (!isConfirmMessage(userMessage)) {
            stateService.clearState(state.getSessionId());
            return ChatResponse.completed(
                    "Başvuru iptal edildi. Başka nasıl yardımcı olabilirim?",
                    "CANCELLED"
            );
        }

        // Başvuru verilerini derle
        String description = (String) state.getData().get("description");
        String tc          = (String) state.getData().get("tc");
        String ad          = (String) state.getData().get("ad");
        String soyad       = (String) state.getData().get("soyad");
        String email       = (String) state.getData().get("email");
        String tel         = (String) state.getData().get("tel");
        String dogum       = (String) state.getData().get("dogum");
        String ilce        = (String) state.getData().getOrDefault("ilce",    "");
        String mahalle     = (String) state.getData().getOrDefault("mahalle", "");
        String cadde       = (String) state.getData().getOrDefault("cadde",   "");
        String binaNo      = (String) state.getData().getOrDefault("binaNo",  "");
        String daireNo     = (String) state.getData().getOrDefault("daireNo", "");

        List<String> filePaths = (List<String>) state.getData().getOrDefault("filePaths", new ArrayList<>());

        // Adres alanını birleştir
        String address = buildAddress(mahalle, cadde, binaNo, daireNo, ilce);

        IbbComplaintService.ComplaintData complaintData = new IbbComplaintService.ComplaintData(
                ad, soyad, tc, email, tel, dogum,
                description, address, ilce,
                filePaths,
                mahalle, cadde, binaNo, daireNo
        );

        log.info("İBB başvurusu gönderiliyor — session={}, tc={}***", state.getSessionId(),
                tc != null && tc.length() >= 3 ? tc.substring(0, 3) : "");

        String srNumber = ibbService.submitComplaint(complaintData);

        stateService.clearState(state.getSessionId());

        if (srNumber == null) {
            return ChatResponse.completed(
                    "Başvurunuz gönderilirken bir hata oluştu. Lütfen daha sonra tekrar deneyin " +
                    "veya doğrudan İBB Çözüm Merkezi'ni arayın: 153",
                    "ERROR"
            );
        }

        return ChatResponse.completed(
                String.format(
                        "Başvurunuz başarıyla İBB Çözüm Merkezi'ne iletildi!\n\n" +
                        "Takip Numaranız: %s\n\n" +
                        "Bu numara ile başvurunuzu https://cozummerkezi.ibb.istanbul adresinden " +
                        "veya 153 hattını arayarak takip edebilirsiniz. " +
                        "Başka nasıl yardımcı olabilirim?",
                        srNumber
                ),
                "DONE"
        );
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private ChatResponse proceedToConfirm(ConversationState state) {
        state.setStep(FlowStep.CONFIRM_COMPLAINT);
        stateService.save(state);

        String summary = buildSummary(state);
        return ChatResponse.waitingInput(
                "Başvuru özetiniz:\n\n" + summary +
                "\n\nBaşvuruyu göndermek istiyor musunuz? (evet / hayır)",
                "CONFIRM_COMPLAINT"
        );
    }

    @SuppressWarnings("unchecked")
    private String buildSummary(ConversationState state) {
        String ad          = (String) state.getData().getOrDefault("ad",          "");
        String soyad       = (String) state.getData().getOrDefault("soyad",       "");
        String tc          = (String) state.getData().getOrDefault("tc",          "");
        String email       = (String) state.getData().getOrDefault("email",       "");
        String tel         = (String) state.getData().getOrDefault("tel",         "");
        String dogum       = (String) state.getData().getOrDefault("dogum",       "");
        String description = (String) state.getData().getOrDefault("description", "");
        String ilce        = (String) state.getData().getOrDefault("ilce",        "");
        String mahalle     = (String) state.getData().getOrDefault("mahalle",     "");
        String cadde       = (String) state.getData().getOrDefault("cadde",       "");
        String binaNo      = (String) state.getData().getOrDefault("binaNo",      "");
        String daireNo     = (String) state.getData().getOrDefault("daireNo",     "");
        List<String> paths = (List<String>) state.getData().getOrDefault("filePaths", new ArrayList<>());

        // TC'nin son 7 hanesini maskele
        String maskedTc = maskTc(tc);

        StringBuilder sb = new StringBuilder();
        sb.append("Ad Soyad  : ").append(ad).append(" ").append(soyad).append("\n");
        sb.append("TC        : ").append(maskedTc).append("\n");
        sb.append("E-posta   : ").append(email).append("\n");
        sb.append("Telefon   : ").append(tel).append("\n");
        sb.append("Doğum     : ").append(dogum).append("\n");
        if (!ilce.isBlank())   sb.append("İlçe      : ").append(ilce).append("\n");
        if (!mahalle.isBlank()) sb.append("Mahalle   : ").append(mahalle).append("\n");
        if (!cadde.isBlank())  sb.append("Cadde/Sk  : ").append(cadde).append("\n");
        if (!binaNo.isBlank()) sb.append("Bina/Daire: ").append(binaNo)
                                .append(daireNo.isBlank() ? "" : "/" + daireNo).append("\n");
        sb.append("Şikayet   : ").append(description).append("\n");
        if (!paths.isEmpty())  sb.append("Dosya     : ").append(paths.size()).append(" adet eklendi");

        return sb.toString();
    }

    private String buildAddress(String mahalle, String cadde, String binaNo, String daireNo, String ilce) {
        StringBuilder sb = new StringBuilder();
        if (!mahalle.isBlank()) sb.append(mahalle).append(" ");
        if (!cadde.isBlank())   sb.append(cadde).append(" ");
        if (!binaNo.isBlank())  sb.append("No:").append(binaNo).append(" ");
        if (!daireNo.isBlank()) sb.append("D:").append(daireNo).append(" ");
        if (!ilce.isBlank())    sb.append(ilce);
        return sb.toString().trim();
    }

    private String maskTc(String tc) {
        if (tc == null || tc.length() < 4) return tc;
        return tc.substring(0, 4) + "*".repeat(tc.length() - 4);
    }

    // ── Parse helpers ─────────────────────────────────────────────────────────

    private String extractGroup(Pattern pattern, String input, int group) {
        Matcher m = pattern.matcher(input);
        return m.find() ? m.group(group).trim() : null;
    }

    private String extractFirstMatch(Pattern pattern, String input) {
        Matcher m = pattern.matcher(input);
        return m.find() ? m.group().trim() : null;
    }

    /**
     * "Etiket: Değer" formatından değer çeker. Virgül veya satır sonu ile sınır belirlenir.
     */
    private String extractLabeledValue(String input, String labelPattern) {
        Pattern p = Pattern.compile("(?i)(?:" + labelPattern + ")[:\\s]+([^,\n]+)");
        Matcher m = p.matcher(input);
        return m.find() ? m.group(1).trim() : null;
    }

    /**
     * Telefon numarasını parse eder; TC numarasıyla çakışmayı önler.
     */
    private String parsePhone(String input) {
        // "Tel:" etiketiyle ara
        Pattern telLabeled = Pattern.compile("(?i)(?:Tel|Telefon)[:\\s]*([0-9+][0-9 ]{8,14})");
        Matcher m = telLabeled.matcher(input);
        if (m.find()) {
            return m.group(1).replaceAll("\\s", "").trim();
        }

        // Etiketsiz: 10 haneli (5 ile başlayan) veya 11 haneli (0 ile başlayan) sayı
        Pattern phoneFree = Pattern.compile("\\b(5[0-9]{9}|0[5][0-9]{9}|\\+90[5][0-9]{9})\\b");
        Matcher m2 = phoneFree.matcher(input);
        return m2.find() ? m2.group(1).trim() : null;
    }

    /**
     * Doğum tarihini parse eder (GG/AA/YYYY veya GG.AA.YYYY veya GG-AA-YYYY).
     * Sonucu MM/DD/YYYY formatına çevirir (İBB API beklentisi).
     */
    private String parseBirthDate(String input) {
        String raw = extractGroup(DOGUM_PATTERN, input, 1);
        if (raw == null) return null;

        // Separatörü normalize et
        String[] parts = raw.split("[./\\-]");
        if (parts.length != 3) return null;

        String day   = parts[0].length() == 1 ? "0" + parts[0] : parts[0];
        String month = parts[1].length() == 1 ? "0" + parts[1] : parts[1];
        String year  = parts[2];

        if (year.length() == 2) year = "19" + year;

        // MM/DD/YYYY döndür (İBB API formatı)
        return month + "/" + day + "/" + year;
    }

    // ── Intent helpers ────────────────────────────────────────────────────────

    private boolean isCancelMessage(String msg) {
        String lower = msg.toLowerCase().trim();
        return CANCEL_WORDS.stream().anyMatch(lower::contains);
    }

    private boolean isSkipMessage(String msg) {
        String lower = msg.toLowerCase().trim();
        return SKIP_WORDS.stream().anyMatch(w -> lower.equals(w) || lower.startsWith(w + " ") || lower.endsWith(" " + w));
    }

    private boolean isConfirmMessage(String msg) {
        String lower = msg.toLowerCase().trim();
        return CONFIRM_WORDS.stream().anyMatch(lower::equals) ||
               lower.startsWith("evet") || lower.startsWith("yes");
    }
}
