package com.chatbot.service;

import com.chatbot.config.ApiMappingConfig;
import com.chatbot.model.ChatResponse;
import com.chatbot.model.ConversationState;
import com.chatbot.model.FlowStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class FlowService {

    private static final Logger log = LoggerFactory.getLogger(FlowService.class);

    private static final Set<String> CANCEL_WORDS = Set.of(
            "cancel", "nevermind", "never mind", "stop", "abort",
            "iptal", "vazgeç", "bırak", "hayır dur"
    );

    // İSKİ entegrasyonu yalnızca su faturası için gerçek API kullanır.
    // BEDAŞ ve İGDAŞ henüz mock'tur (CAPTCHA / login duvarı).
    private static final Set<String> REAL_API_ENTITIES = Set.of("water", "su");

    private final ConversationStateService stateService;
    private final ApiMappingConfig apiMappingConfig;
    private final IskiService iskiService;

    public FlowService(ConversationStateService stateService,
                       ApiMappingConfig apiMappingConfig,
                       IskiService iskiService) {
        this.stateService    = stateService;
        this.apiMappingConfig = apiMappingConfig;
        this.iskiService     = iskiService;
    }

    public ChatResponse startFlow(String sessionId, String intent, String entity) {
        ConversationState state = stateService.createFlowState(sessionId, intent, entity);
        log.info("Flow started — session={}, intent={}, entity={}", sessionId, intent, entity);
        return askForInput(state);
    }

    public ChatResponse continueFlow(ConversationState state, String userMessage) {
        if (isCancelMessage(userMessage)) {
            stateService.clearState(state.getSessionId());
            return ChatResponse.completed("İşlem iptal edildi. Başka nasıl yardımcı olabilirim?", "CANCELLED");
        }

        return switch (state.getStep()) {
            case ASK_SUBSCRIBER_NO -> processInput(state, userMessage);
            case CONFIRM_PAYMENT   -> processConfirmation(state, userMessage);
            default -> {
                stateService.clearState(state.getSessionId());
                yield ChatResponse.completed("Bir hata oluştu, lütfen tekrar başlayın.", "ERROR");
            }
        };
    }

    // ── Step handlers ─────────────────────────────────────────────────────────

    private ChatResponse askForInput(ConversationState state) {
        String provider = apiMappingConfig.getProvider(state.getEntity());
        if (isRealApiEntity(state.getEntity())) {
            return ChatResponse.waitingInput(
                    String.format("Su faturanızı sorgulamak için TC kimlik numaranızı giriniz (%s).", provider),
                    "ASK_SUBSCRIBER_NO"
            );
        }
        return ChatResponse.waitingInput(
                String.format("%s abone numaranızı giriniz (%s).", capitalize(state.getEntity()), provider),
                "ASK_SUBSCRIBER_NO"
        );
    }

    private ChatResponse processInput(ConversationState state, String userMessage) {
        String input = userMessage.trim();
        if (input.isBlank()) {
            return ChatResponse.waitingInput("Lütfen geçerli bir numara giriniz.", "ASK_SUBSCRIBER_NO");
        }

        state.getData().put("subscriberNo", input);
        state.setStep(FlowStep.FETCH_BILL);
        stateService.save(state);

        // İSKİ gerçek API
        if (isRealApiEntity(state.getEntity())) {
            return fetchRealIskiBill(state, input);
        }

        // Mock (BEDAŞ, İGDAŞ)
        return fetchMockBill(state, input);
    }

    private ChatResponse fetchRealIskiBill(ConversationState state, String tcNo) {
        String provider = apiMappingConfig.getProvider(state.getEntity());
        log.info("Calling İSKİ API for TC={}", tcNo.replaceAll(".", "*").substring(0, Math.min(tcNo.length(), 8)) + "***");

        IskiService.IskiResult result = iskiService.queryByTcNo(tcNo);

        if (!result.success()) {
            // TC geçersiz ya da bağlantı hatası — state'i sıfırla
            stateService.clearState(state.getSessionId());
            return ChatResponse.completed(
                    String.format("İSKİ sorgusunda hata: %s", result.errorMessage()),
                    "ERROR"
            );
        }

        List<IskiService.BillInfo> bills = result.bills();

        if (bills.isEmpty()) {
            stateService.clearState(state.getSessionId());
            return ChatResponse.completed(
                    String.format("Bu TC kimlik numarasına (%s) bağlı İSKİ borcu bulunmamaktadır.", provider),
                    "COMPLETED"
            );
        }

        // Birden fazla fatura olabilir — toplamı al veya ilkini göster
        IskiService.BillInfo first = bills.get(0);
        String amount = first.tutar().equals("-") ? "?" : first.tutar();
        String aboneNo = first.aboneNo().equals("-") ? tcNo : first.aboneNo();

        String billDetails;
        if (bills.size() > 1) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%d adet İSKİ borcunuz bulunmaktadır (%s):\n", bills.size(), provider));
            for (int i = 0; i < Math.min(bills.size(), 3); i++) {
                IskiService.BillInfo b = bills.get(i);
                sb.append(String.format("  • Abone: %s — ₺%s\n", b.aboneNo(), b.tutar()));
            }
            sb.append(String.format("Toplam ilk fatura: ₺%s. Ödemeyi onaylamak ister misiniz? (evet / hayır)", amount));
            billDetails = sb.toString();
        } else {
            billDetails = String.format(
                    "İSKİ su faturanız (%s) — Abone No: %s, Tutar: ₺%s.\nÖdemeyi onaylamak ister misiniz? (evet / hayır)",
                    provider, aboneNo, amount
            );
        }

        state.getData().put("amount", amount);
        state.getData().put("aboneNo", aboneNo);
        state.getData().put("realApi", "true");
        state.setStep(FlowStep.CONFIRM_PAYMENT);
        stateService.save(state);

        return ChatResponse.waitingInput(billDetails, "CONFIRM_PAYMENT");
    }

    private ChatResponse fetchMockBill(ConversationState state, String subscriberNo) {
        BigDecimal amount = mockFetchBill(state.getEntity(), subscriberNo);
        String provider   = apiMappingConfig.getProvider(state.getEntity());

        state.getData().put("amount", amount.toPlainString());
        state.setStep(FlowStep.CONFIRM_PAYMENT);
        stateService.save(state);

        return ChatResponse.waitingInput(
                String.format("%s faturanız (%s) tutarı ₺%s'dir. Ödemeyi onaylamak ister misiniz? (evet / hayır)",
                        capitalize(state.getEntity()), provider, amount),
                "CONFIRM_PAYMENT"
        );
    }

    private ChatResponse processConfirmation(ConversationState state, String userMessage) {
        if (!isAffirmative(userMessage)) {
            stateService.clearState(state.getSessionId());
            return ChatResponse.completed(
                    "Ödeme iptal edildi. Faturanızdan herhangi bir kesinti yapılmadı. Başka nasıl yardımcı olabilirim?",
                    "CANCELLED"
            );
        }

        String subscriberNo = (String) state.getData().getOrDefault("aboneNo",
                                        state.getData().get("subscriberNo"));
        String amount   = (String) state.getData().get("amount");
        String provider = apiMappingConfig.getProvider(state.getEntity());
        long   txnId    = ThreadLocalRandom.current().nextLong(100_000L, 999_999L);

        stateService.clearState(state.getSessionId());

        return ChatResponse.completed(
                String.format("✓ %s aboneniz (%s) için ₺%s tutarındaki ödeme başarıyla gerçekleşti. " +
                              "İşlem No: TXN-%d. Başka nasıl yardımcı olabilirim?",
                        provider, subscriberNo, amount, txnId),
                "DONE"
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isRealApiEntity(String entity) {
        return entity != null && REAL_API_ENTITIES.contains(entity.toLowerCase());
    }

    private BigDecimal mockFetchBill(String entity, String subscriberNo) {
        int cents = Math.abs(subscriberNo.hashCode() % 40000) + 500;
        return new BigDecimal(cents).divide(BigDecimal.TEN, 2, RoundingMode.HALF_UP);
    }

    private boolean isCancelMessage(String msg) {
        String lower = msg.toLowerCase().trim();
        return CANCEL_WORDS.stream().anyMatch(lower::contains);
    }

    private boolean isAffirmative(String msg) {
        String lower = msg.toLowerCase().trim();
        return lower.startsWith("evet") || lower.startsWith("yes") ||
               lower.equals("e") || lower.equals("y") || lower.equals("ok") ||
               lower.equals("onay") || lower.equals("onayla");
    }

    private String capitalize(String s) {
        if (s == null || s.isBlank()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }
}
