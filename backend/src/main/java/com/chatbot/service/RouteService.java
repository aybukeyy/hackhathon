package com.chatbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RouteService {

    private static final Logger log = LoggerFactory.getLogger(RouteService.class);

    private static final String DURAK_URL  = "https://api.ibb.gov.tr/iett/ibb/ibb.asmx";
    private static final String METRO_URL  = "https://api.ibb.gov.tr/MetroIstanbul/api/MetroMobile/V2/GetStations";
    private static final String OSRM_WALK  = "http://router.project-osrm.org/route/v1/foot/";
    private static final String OSRM_DRIVE = "http://router.project-osrm.org/route/v1/driving/";
    private static final String NOMINATIM  = "https://nominatim.openstreetmap.org/search?format=json&limit=1&q=";

    private static final List<String> BUS_LINES = List.of(
        "34B", "34AS", "34A", "34", "T1", "T2", "T4", "500T", "28"
    );

    private static final Map<String, String> LINE_TYPE;
    static {
        LINE_TYPE = new HashMap<>();
        LINE_TYPE.put("T1", "tram"); LINE_TYPE.put("T2", "tram"); LINE_TYPE.put("T4", "tram");
        LINE_TYPE.put("M1A", "metro"); LINE_TYPE.put("M1B", "metro"); LINE_TYPE.put("M2", "metro");
        LINE_TYPE.put("M3", "metro"); LINE_TYPE.put("M4", "metro"); LINE_TYPE.put("M5", "metro");
        LINE_TYPE.put("M6", "metro"); LINE_TYPE.put("M7", "metro");
    }

    private final Map<String, List<Stop>> cache = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;

    public RouteService() {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public Map<String, Object> findRoute(double userLat, double userLon, String destination) throws Exception {
        // 1. Geocode
        double[] dest = geocode(destination);
        if (dest == null) throw new RuntimeException("Hedef konum bulunamadı: " + destination);

        // 2. Load stops
        List<Stop> allStops = loadAllStops();
        if (allStops.isEmpty()) throw new RuntimeException("Durak verisi alınamadı.");

        // 3. Nearest boarding stop to user
        Stop board = nearest(userLat, userLon, allStops);
        // 4. Nearest alighting stop to destination
        Stop alight = nearest(dest[0], dest[1], allStops);

        List<Map<String, Object>> segments = new ArrayList<>();
        List<Map<String, Object>> markers  = new ArrayList<>();

        markers.add(marker(userLat, userLon, "📍 Başlangıç", "start"));
        markers.add(marker(dest[0], dest[1], "🏁 " + destination, "end"));

        if (board.line.equals(alight.line)) {
            // Direct
            buildSegments(segments, markers, userLat, userLon, board, alight, dest[0], dest[1]);
        } else {
            // Find transfer
            Stop transfer = findTransfer(board, alight, allStops);
            if (transfer == null) {
                // No transfer found, just go board→alight directly
                buildSegments(segments, markers, userLat, userLon, board, alight, dest[0], dest[1]);
            } else {
                Stop alightFirst  = nearestOnLine(transfer.lat, transfer.lon, board.line, allStops);
                Stop boardSecond  = nearestOnLine(transfer.lat, transfer.lon, alight.line, allStops);
                buildSegments(segments, markers, userLat, userLon, board, alightFirst, boardSecond.lat, boardSecond.lon);
                buildSegments(segments, markers, boardSecond.lat, boardSecond.lon, boardSecond, alight, dest[0], dest[1]);
            }
        }

        String summary = buildSummary(segments);
        String spoken  = buildSpokenSummary(segments);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("segments", segments); result.put("markers", markers);
        result.put("summary", summary);   result.put("spokenSummary", spoken);
        result.put("destLat", dest[0]);   result.put("destLon", dest[1]);
        return result;
    }

    // ── Segment builder ───────────────────────────────────────────────────────

    private void buildSegments(List<Map<String, Object>> segs, List<Map<String, Object>> marks,
                               double fromLat, double fromLon, Stop board, Stop alight,
                               double toLat, double toLon) throws Exception {
        // Walk to board stop
        List<double[]> walkPoly1 = osrm(OSRM_WALK, fromLat, fromLon, board.lat, board.lon);
        segs.add(segment("walk", null, "Yürüyüş → " + board.name, "#22c55e", walkPoly1));
        marks.add(marker(board.lat, board.lon, "🚌 " + board.line + "'e bin: " + board.name, "board"));

        // Transit segment (stops between board and alight on same line)
        List<Stop> lineStops = stopsOnLine(board, alight);
        List<double[]> transitPoly = osrmMulti(lineType(board.line).equals("metro") ? OSRM_WALK : OSRM_DRIVE, lineStops);
        String transitDesc = lineLabel(board.line) + " ile " + board.name + " → " + alight.name;
        segs.add(segment(lineType(board.line), board.line, transitDesc, lineColor(board.line), transitPoly));
        marks.add(marker(alight.lat, alight.lon, "⬇️ " + board.line + "'den in: " + alight.name, "alight"));

        // Walk to destination
        List<double[]> walkPoly2 = osrm(OSRM_WALK, alight.lat, alight.lon, toLat, toLon);
        segs.add(segment("walk", null, "Yürüyüş → Hedefe", "#22c55e", walkPoly2));
    }

    // ── Stop loading ──────────────────────────────────────────────────────────

    private List<Stop> loadAllStops() {
        List<Stop> all = new ArrayList<>();
        for (String line : BUS_LINES) {
            all.addAll(cache.computeIfAbsent(line, this::loadBusStops));
        }
        all.addAll(cache.computeIfAbsent("__metro__", k -> loadMetroStops()));
        return all;
    }

    private List<Stop> loadBusStops(String hatKodu) {
        try {
            String soap = "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
                "<soap:Body><DurakDetay_GYY xmlns=\"http://tempuri.org/\">" +
                "<hat_kodu>" + hatKodu + "</hat_kodu>" +
                "</DurakDetay_GYY></soap:Body></soap:Envelope>";
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(DURAK_URL))
                .POST(HttpRequest.BodyPublishers.ofString(soap))
                .header("Content-Type", "text/xml; charset=utf-8")
                .header("SOAPAction", "http://tempuri.org/DurakDetay_GYY")
                .timeout(Duration.ofSeconds(10)).build();
            String body = http.send(req, HttpResponse.BodyHandlers.ofString()).body();

            List<Stop> stops = new ArrayList<>();
            Pattern p = Pattern.compile(
                "<HATKODU>([^<]+)</HATKODU><YON>([^<]+)</YON><SIRANO>(\\d+)</SIRANO>" +
                "<DURAKKODU>([^<]+)</DURAKKODU><DURAKADI>([^<]+)</DURAKADI>" +
                "<XKOORDINATI>([^<]+)</XKOORDINATI><YKOORDINATI>([^<]+)</YKOORDINATI>");
            Matcher m = p.matcher(body);
            while (m.find()) {
                try {
                    stops.add(new Stop(m.group(4), m.group(5), m.group(1),
                        Double.parseDouble(m.group(7)), Double.parseDouble(m.group(6)),
                        Integer.parseInt(m.group(3)), m.group(2)));
                } catch (Exception ignored) {}
            }
            log.info("Loaded {} stops for line {}", stops.size(), hatKodu);
            return stops;
        } catch (Exception e) {
            log.warn("Could not load stops for {}: {}", hatKodu, e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<Stop> loadMetroStops() {
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(METRO_URL))
                .GET().header("Accept", "application/json").timeout(Duration.ofSeconds(10)).build();
            JsonNode data = mapper.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body()).path("Data");
            List<Stop> stops = new ArrayList<>();
            for (JsonNode s : data) {
                String latStr = s.path("DetailInfo").path("Latitude").asText("");
                String lonStr = s.path("DetailInfo").path("Longitude").asText("");
                if (latStr.isBlank() || lonStr.isBlank()) continue;
                String line = s.path("LineName").asText("Metro");
                stops.add(new Stop(s.path("StationCode").asText(""), s.path("Description").asText(""),
                    line, Double.parseDouble(latStr), Double.parseDouble(lonStr), 0, "D"));
            }
            log.info("Loaded {} metro stops", stops.size());
            return stops;
        } catch (Exception e) {
            log.warn("Metro stops load failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ── OSRM ──────────────────────────────────────────────────────────────────

    private List<double[]> osrm(String base, double lat1, double lon1, double lat2, double lon2) {
        try {
            String url = base + lon1 + "," + lat1 + ";" + lon2 + "," + lat2 + "?overview=full&geometries=geojson";
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .GET().header("User-Agent", "IBBBot/1.0").timeout(Duration.ofSeconds(8)).build();
            JsonNode coords = mapper.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body())
                .path("routes").path(0).path("geometry").path("coordinates");
            List<double[]> poly = new ArrayList<>();
            for (JsonNode c : coords) poly.add(new double[]{c.get(1).asDouble(), c.get(0).asDouble()});
            return poly;
        } catch (Exception e) {
            log.warn("OSRM failed: {}", e.getMessage());
            return List.of(new double[]{lat1, lon1}, new double[]{lat2, lon2});
        }
    }

    private List<double[]> osrmMulti(String base, List<Stop> stops) {
        if (stops.size() < 2) return Collections.emptyList();
        // Limit waypoints to avoid too-long URLs
        List<Stop> sampled = sample(stops, 15);
        StringBuilder sb = new StringBuilder();
        for (Stop s : sampled) {
            if (sb.length() > 0) sb.append(";");
            sb.append(s.lon).append(",").append(s.lat);
        }
        try {
            String url = base + sb + "?overview=full&geometries=geojson";
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .GET().header("User-Agent", "IBBBot/1.0").timeout(Duration.ofSeconds(10)).build();
            JsonNode coords = mapper.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body())
                .path("routes").path(0).path("geometry").path("coordinates");
            List<double[]> poly = new ArrayList<>();
            for (JsonNode c : coords) poly.add(new double[]{c.get(1).asDouble(), c.get(0).asDouble()});
            return poly;
        } catch (Exception e) {
            log.warn("OSRM multi failed: {}", e.getMessage());
            List<double[]> fallback = new ArrayList<>();
            for (Stop s : sampled) fallback.add(new double[]{s.lat, s.lon});
            return fallback;
        }
    }

    // ── Geocoding ─────────────────────────────────────────────────────────────

    private double[] geocode(String place) {
        try {
            String url = NOMINATIM + URLEncoder.encode(place + " Istanbul", StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                .GET().header("User-Agent", "IBBBot/1.0").timeout(Duration.ofSeconds(8)).build();
            JsonNode results = mapper.readTree(http.send(req, HttpResponse.BodyHandlers.ofString()).body());
            if (results.isArray() && results.size() > 0) {
                return new double[]{results.get(0).path("lat").asDouble(), results.get(0).path("lon").asDouble()};
            }
        } catch (Exception e) { log.warn("Geocode failed: {}", e.getMessage()); }
        return null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Stop nearest(double lat, double lon, List<Stop> stops) {
        return stops.stream().min(Comparator.comparingDouble(s -> dist(lat, lon, s.lat, s.lon))).orElseThrow();
    }

    private Stop nearestOnLine(double lat, double lon, String line, List<Stop> stops) {
        return stops.stream().filter(s -> s.line.equals(line))
            .min(Comparator.comparingDouble(s -> dist(lat, lon, s.lat, s.lon))).orElse(nearest(lat, lon, stops));
    }

    private Stop findTransfer(Stop board, Stop alight, List<Stop> allStops) {
        List<Stop> lineA = allStops.stream().filter(s -> s.line.equals(board.line)).toList();
        List<Stop> lineB = allStops.stream().filter(s -> s.line.equals(alight.line)).toList();
        double minDist = Double.MAX_VALUE;
        Stop best = null;
        for (Stop a : lineA) {
            for (Stop b : lineB) {
                double d = dist(a.lat, a.lon, b.lat, b.lon);
                if (d < minDist) { minDist = d; best = a; }
            }
        }
        return minDist < 0.5 ? best : null; // within 500m
    }

    private List<Stop> stopsOnLine(Stop from, Stop to) {
        List<Stop> line = cache.values().stream().flatMap(List::stream)
            .filter(s -> s.line.equals(from.line) && s.direction.equals(from.direction))
            .sorted(Comparator.comparingInt(s -> s.order))
            .toList();
        int i = line.indexOf(from), j = line.indexOf(to);
        if (i < 0 || j < 0 || i == j) return List.of(from, to);
        if (i > j) { int tmp = i; i = j; j = tmp; }
        return line.subList(i, j + 1);
    }

    private List<Stop> sample(List<Stop> stops, int max) {
        if (stops.size() <= max) return stops;
        List<Stop> result = new ArrayList<>();
        double step = (double) stops.size() / max;
        for (int i = 0; i < max; i++) result.add(stops.get((int)(i * step)));
        return result;
    }

    private double dist(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat/2)*Math.sin(dLat/2) +
            Math.cos(Math.toRadians(lat1))*Math.cos(Math.toRadians(lat2))*Math.sin(dLon/2)*Math.sin(dLon/2);
        return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    }

    private String lineType(String line)  { return LINE_TYPE.getOrDefault(line, "bus"); }
    private String lineColor(String line) {
        return switch (lineType(line)) {
            case "tram"  -> "#ef4444";
            case "metro" -> "#8b5cf6";
            default      -> "#3b82f6";
        };
    }
    private String lineLabel(String line) {
        return switch (lineType(line)) {
            case "tram"  -> "🚋 " + line + " Tramvayı";
            case "metro" -> "🚇 " + line + " Metrosu";
            default      -> "🚌 " + line + " Otobüsü";
        };
    }

    private String buildSummary(List<Map<String, Object>> segs) {
        long transit = segs.stream().filter(s -> !"walk".equals(s.get("type"))).count();
        return "Rota bulundu — " + transit + " toplu taşıma, " + (transit > 1 ? (transit-1) + " aktarma" : "direkt");
    }

    private String buildSpokenSummary(List<Map<String, Object>> segs) {
        StringBuilder sb = new StringBuilder("Rota hazır. ");
        for (Map<String, Object> seg : segs) {
            String desc = String.valueOf(seg.getOrDefault("description", ""));
            // Emoji ve özel karakterleri temizle
            desc = desc.replaceAll("[^\\p{L}\\p{N}\\s,→'.]", "").replace("→", "→").trim();
            if (!desc.isBlank()) sb.append(desc).append(". ");
        }
        return sb.toString().trim();
    }

    private Map<String, Object> segment(String type, String line, String desc, String color, List<double[]> poly) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type); m.put("line", line); m.put("description", desc);
        m.put("color", color); m.put("polyline", poly);
        return m;
    }

    private Map<String, Object> marker(double lat, double lon, String label, String role) {
        return Map.of("lat", lat, "lon", lon, "label", label, "role", role);
    }

    record Stop(String code, String name, String line, double lat, double lon, int order, String direction) {}
}
