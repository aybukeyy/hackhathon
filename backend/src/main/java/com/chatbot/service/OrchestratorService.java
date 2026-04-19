package com.chatbot.service;

import com.chatbot.model.ChatRequest;
import com.chatbot.model.ChatResponse;
import com.chatbot.model.ConversationState;
import com.chatbot.model.FlowStep;
import com.chatbot.model.LLMResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;

@Service
public class OrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorService.class);

    private static final Set<String> FLOW_INTENTS      = Set.of("pay_bill", "ibb_complaint");
    private static final Set<String> COMPLAINT_INTENTS = Set.of("ibb_complaint");
    private static final Set<String> API_INTENTS       = Set.of("metro_status", "metro_ticket", "nearest_station", "traffic_info",
                                                                   "metro_projects", "metro_activities", "metro_news",
                                                                   "metro_maps", "bus_location", "route_to", "current_location");
    private static final double      CONFIDENCE_THRESHOLD = 0.6;

    private final ConversationStateService stateService;
    private final LLMService               llmService;
    private final FlowService              flowService;
    private final ComplaintFlowService     complaintFlowService;
    private final KnowledgeService         knowledgeService;
    private final MetroService             metroService;
    private final TrafficService           trafficService;
    private final IettService              iettService;
    private final RouteService             routeService;

    public OrchestratorService(ConversationStateService stateService,
                               LLMService llmService,
                               FlowService flowService,
                               ComplaintFlowService complaintFlowService,
                               KnowledgeService knowledgeService,
                               MetroService metroService,
                               TrafficService trafficService,
                               IettService iettService,
                               RouteService routeService) {
        this.stateService         = stateService;
        this.llmService           = llmService;
        this.flowService          = flowService;
        this.complaintFlowService = complaintFlowService;
        this.knowledgeService     = knowledgeService;
        this.metroService         = metroService;
        this.trafficService       = trafficService;
        this.iettService          = iettService;
        this.routeService         = routeService;
    }

    public ChatResponse process(ChatRequest request) {
        String sessionId = request.getSessionId();
        String message   = request.getMessage().trim();

        log.info("Processing — session={}, message='{}'", sessionId, message);

        // Konum bilgisi varsa state'e kaydet ve logla
        if (request.getLat() != null && request.getLng() != null) {
            ConversationState loc = stateService.getOrCreate(sessionId);
            loc.getData().put("lat", request.getLat());
            loc.getData().put("lng", request.getLng());
            stateService.save(loc);
            log.info("Konum alındı — session={}, lat={}, lng={}", sessionId, request.getLat(), request.getLng());
        }

        // 1. Aktif flow varsa LLM'e gitme, devam et
        Optional<ConversationState> existing = stateService.getState(sessionId);
        if (existing.isPresent() && stateService.hasActiveFlow(sessionId)) {
            ConversationState state = existing.get();
            log.info("Active flow — intent={}, step={}", state.getIntent(), state.getStep());
            if (isComplaintFlow(state)) return complaintFlowService.continueComplaintFlow(state, message);
            return flowService.continueFlow(state, message);
        }

        // 2. Keyword override — LLM'den önce
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        boolean isLocationQuery = lower.contains("neredeyim") || lower.contains("nerdeyim")
                || lower.contains("konumum") || lower.contains("bulundugum")
                || lower.contains("nerde yim") || lower.contains("nere yim");
        if (isLocationQuery) {
            ConversationState kState = stateService.getOrCreate(sessionId);
            return reverseGeocodeResponse(kState);
        }

        // 3. LLM ile intent çıkar
        LLMResult llm = llmService.extractIntent(message);
        log.info("LLM → {}", llm);

        // 3. Düşük güven → açıklama iste
        if (llm.getConfidence() < CONFIDENCE_THRESHOLD) {
            return knowledgeService.clarificationResponse();
        }

        // 4. Intent'e göre yönlendir
        String intent = llm.getIntent();

        if (FLOW_INTENTS.contains(intent)) {
            if (COMPLAINT_INTENTS.contains(intent)) return complaintFlowService.startComplaintFlow(sessionId);
            return flowService.startFlow(sessionId, intent, llm.getEntity());
        }

        if (API_INTENTS.contains(intent)) {
            if ("current_location".equals(intent)) {
                ConversationState locState = stateService.getOrCreate(sessionId);
                return reverseGeocodeResponse(locState);
            }
            return handleApiIntent(intent, sessionId, llm);
        }

        // Knowledge mode (general_question, route_question, unknown)
        return knowledgeService.generateResponse(message, intent);
    }

    // ── API intent handler ────────────────────────────────────────────────────

    private ChatResponse handleApiIntent(String intent, String sessionId, LLMResult llm) {
        ConversationState state = stateService.getOrCreate(sessionId);

        if ("route_to".equals(intent)) {
            String dest = llm.getEntity();
            if (dest == null || dest.isBlank()) return ChatResponse.completed("Nereye gitmek istediğinizi belirtin. Örnek: 'Eminönü'ne nasıl giderim?'", "API_RESPONSE");
            Object lat = state.getData().get("lat");
            Object lon = state.getData().get("lng");
            if (lat == null || lon == null) return ChatResponse.completed("Konumunuzu paylaşmadığınız için rota çizemiyorum. Lütfen tarayıcı konum iznini etkinleştirin.", "API_RESPONSE");
            try {
                var routeData = routeService.findRoute(((Number)lat).doubleValue(), ((Number)lon).doubleValue(), dest);
                ChatResponse resp = ChatResponse.completed((String) routeData.get("summary"), "ROUTE");
                resp.setRouteData(routeData);
                return resp;
            } catch (Exception e) {
                log.error("Route error: {}", e.getMessage());
                return ChatResponse.completed("Rota hesaplanırken hata oluştu: " + e.getMessage(), "API_RESPONSE");
            }
        }

        String result = switch (intent) {
            case "metro_status"      -> metroService.getServiceStatus();
            case "metro_ticket"      -> metroService.getTicketPrices();
            case "nearest_station"   -> metroService.getNearestStation(state);
            case "traffic_info"      -> trafficService.getTrafficInfo();
            case "metro_projects"    -> metroService.getLineProjects();
            case "metro_activities"  -> metroService.getActivities();
            case "metro_news"        -> metroService.getNews();
            case "metro_maps"        -> metroService.getMaps();
            case "bus_location"      -> iettService.getBusLocation(llm.getEntity());
            case "current_location"  -> reverseGeocode(state);
            default                  -> null;
        };
        if (result == null) return knowledgeService.clarificationResponse();
        return ChatResponse.completed(result, "API_RESPONSE");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ChatResponse reverseGeocodeResponse(ConversationState state) {
        Object lat = state.getData().get("lat");
        Object lon = state.getData().get("lng");
        String text = reverseGeocode(state);
        if (lat == null || lon == null) return ChatResponse.completed(text, "API_RESPONSE");

        java.util.Map<String, Object> pinData = new java.util.HashMap<>();
        pinData.put("type", "pin");
        java.util.Map<String, Object> mk = new java.util.HashMap<>();
        mk.put("lat", ((Number) lat).doubleValue());
        mk.put("lon", ((Number) lon).doubleValue());
        mk.put("label", "📍 Buradasınız");
        pinData.put("markers", java.util.List.of(mk));
        pinData.put("segments", java.util.List.of());

        ChatResponse resp = ChatResponse.completed(text, "API_RESPONSE");
        resp.setRouteData(pinData);
        return resp;
    }

    private String reverseGeocode(ConversationState state) {
        Object lat = state.getData().get("lat");
        Object lon = state.getData().get("lng");
        if (lat == null || lon == null) return "Konumunuzu paylaşmadığınız için bulunduğunuz yeri gösteremiyorum.";
        try {
            String url = "https://nominatim.openstreetmap.org/reverse?format=json&lat=" + lat + "&lon=" + lon;
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .header("User-Agent", "IBBBot/1.0")
                .timeout(java.time.Duration.ofSeconds(8)).build();
            String body = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).body();
            com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            String display = node.path("display_name").asText("");
            String road    = node.path("address").path("road").asText("");
            String suburb  = node.path("address").path("suburb").asText(node.path("address").path("neighbourhood").asText(""));
            String district = node.path("address").path("district").asText(node.path("address").path("county").asText(""));
            if (!road.isBlank()) return "📍 Şu an buradasınız: " + road + (suburb.isBlank() ? "" : ", " + suburb) + (district.isBlank() ? "" : ", " + district);
            return "📍 Şu an buradasınız: " + display;
        } catch (Exception e) {
            return "Konum bilgisi alınamadı.";
        }
    }

    private boolean isComplaintFlow(ConversationState state) {
        if ("ibb_complaint".equals(state.getIntent())) return true;
        FlowStep step = state.getStep();
        return step == FlowStep.ASK_COMPLAINT_DESCRIPTION
            || step == FlowStep.ASK_COMPLAINT_PERSONAL
            || step == FlowStep.ASK_COMPLAINT_PHOTO
            || step == FlowStep.CONFIRM_COMPLAINT;
    }
}
