# İBBot — İstanbul Büyükşehir Belediyesi AI Asistanı

İBBot, İstanbul sakinlerine yönelik geliştirilmiş çok katmanlı bir yapay zeka asistanıdır. Gerçek zamanlı İBB API'leri, yerel LLM (Ollama), vektör tabanlı RAG sistemi ve etkileşimli harita entegrasyonunu tek bir chatbot arayüzünde birleştirir.

---

## Mimari

```
┌─────────────────────────────────────────────────────────┐
│                   Frontend (React + Vite)                │
│   ChatWindow · InputBar · MapPanel · MessageBubble       │
│   Sesli giriş (Web Speech API) · Sesli çıkış (TTS)      │
└───────────────────────┬─────────────────────────────────┘
                        │ HTTP /api/chat
┌───────────────────────▼─────────────────────────────────┐
│              Backend (Spring Boot :8080)                  │
│                                                          │
│  OrchestratorService                                     │
│  ├── Keyword override (neredeyim → reverseGeocode)       │
│  ├── Active flow check (fatura/şikayet akışları)         │
│  ├── LLMService → intent + entity extraction             │
│  └── Intent Router                                       │
│       ├── FLOW_INTENTS    → FlowService / ComplaintFlow  │
│       ├── API_INTENTS     → MetroService / IettService   │
│       │                     TrafficService / RouteService│
│       └── KNOWLEDGE       → KnowledgeService             │
│                                 ├── RAG Service (Python) │
│                                 └── LLMService fallback  │
└──────┬──────────┬──────────┬────────────────────────────┘
       │          │          │
  Ollama     İBB APIs    RAG Service
  :11434    (external)   (FastAPI :8001)
                          └── embeddinggemma + rag_index.pkl
```

### Bir İstek Nasıl İşlenir?

1. Kullanıcı mesajı yazıp gönderir (metin veya sesli)
2. Frontend GPS konumunu da POST body'e ekler
3. Backend `OrchestratorService.process()` devreye girer:
   - Aktif fatura/şikayet akışı varsa → akışa devam
   - "neredeyim", "konumum" gibi kelimeler varsa → Nominatim reverse geocode
   - Yoksa `LLMService.extractIntent()` ile Ollama'dan JSON intent çıkarır
   - Intent'e göre ilgili servis çağrılır
4. Response `ChatResponse` modeli ile döner (`message` + isteğe bağlı `routeData`)
5. Frontend mesajı gösterir; `routeData` varsa `MapPanel` haritayı çizer

---

## Teknoloji Yığını

### Frontend
| Teknoloji | Versiyon | Kullanım Amacı |
|---|---|---|
| React | 18 | UI framework |
| Vite | 5 | Build tool + dev server |
| Tailwind CSS | 4 | Stil |
| Leaflet.js | 1.9 | Etkileşimli harita (rota, pin) |
| Web Speech API | — | Türkçe sesli giriş (`tr-TR`) |
| SpeechSynthesis API | — | Türkçe sesli yanıt (TTS) |
| Lucide React | — | İkonlar |

### Backend
| Teknoloji | Versiyon | Kullanım Amacı |
|---|---|---|
| Spring Boot | 3.2.3 | REST API framework |
| Java | 17 | Dil |
| Jackson | — | JSON parse/serialize |
| Java HttpClient | — | Dış API çağrıları |

### AI / ML
| Teknoloji | Kullanım Amacı |
|---|---|
| Ollama 0.21.0 | Yerel LLM sunucusu |
| gemma3:4b | Intent sınıflandırma + serbest yanıt üretimi |
| embeddinggemma:300m-bf16 | RAG vektör embedding modeli |
| FastAPI (Python) | RAG microservice |

### Altyapı
| Teknoloji | Kullanım Amacı |
|---|---|
| Docker + Docker Compose | Servis orkestrasyonu |
| nginx | Frontend static serve + /api proxy |
| OpenStreetMap / Nominatim | Adres geocoding + reverse geocoding |
| OSRM | Gerçek sokak bazlı rota polyline hesaplama |

---

## Servisler ve Sorumlulukları

### OrchestratorService
Tüm gelen isteklerin tek giriş noktası. Şu sırayla karar verir:
1. Aktif multi-step akış var mı? (fatura, şikayet)
2. Konum sorusu mu? (keyword override — LLM'e gitmeden)
3. LLM intent → yönlendirme

### LLMService
Ollama'ya prompt gönderir, intent+entity JSON çıkarır. 17 intent tanır. 2 retry, 3 parse stratejisi (direct → strip markdown → regex) uygular. Şikayet metni validasyonu da yapar.

### KnowledgeService
Önce RAG servisine sorar. RAG bulamazsa (`found: false`) LLM'e düşer. İki katmanlı bilgi erişimi sağlar.

### RouteService
GPS konumu + hedef adı alır:
1. Nominatim ile hedefi geocode eder
2. IETT SOAP API'den otobüs durak koordinatları çeker
3. Metro İstanbul API'den metro durakları çeker
4. En yakın durak → aktarma noktası → hedef zincirini kurar
5. OSRM ile her segment için sokak bazlı polyline alır
6. Renk kodlu segmentler + konuşulabilir özet döner

### RAG Service (Python / FastAPI)
34.273 İBB kayıdını barındıran vektör indeks üzerinde çalışır:
- `embeddinggemma:300m-bf16` ile 768 boyutlu vektörler
- Cosine similarity (0.72) + keyword overlap (max 0.45) + metadata boost (max 0.20) ile reranking
- TOP_K_CANDIDATES=80 → TOP_K_CONTEXT=8
- Threshold 0.30 (relevance) / 0.35 (answer)
- Koordinat verileri `locations[]` olarak ayrıca döner → haritada pin

---

## Kullanılan İBB / Dış API'ler

### Metro İstanbul
**Base:** `https://api.ibb.gov.tr/MetroIstanbul/api`

| Endpoint | Örnek Soru |
|---|---|
| `GET /MetroMobile/V2/GetServiceStatuses` | "Metro çalışıyor mu?" |
| `GET /MetroMobile/V2/GetAnnouncements/tr` | "Metro duyuruları neler?" |
| `GET /MetroMobile/V2/GetTicketPrice/tr` | "Metro bileti kaç lira?" |
| `GET /MetroMobile/V2/GetStations` | "En yakın metro istasyonu nerede?" |
| `GET /MetroMobile/V2/GetLineProjects` | "Hangi metro hatları yapılıyor?" |
| `GET /MetroMobile/V2/GetActivities` | "Metroda etkinlik var mı?" |
| `GET /MetroMobile/V2/GetNews/tr` | "Metro haberleri neler?" |
| `GET /MetroMobile/V2/GetMaps` | "Metro haritası" |

---

### IETT
**Base:** `https://api.ibb.gov.tr/iett`

| Endpoint | Örnek Soru |
|---|---|
| `SOAP GetHatOtoKonum_json` (`/FiloDurum/SeferGerceklesme.asmx`) | "34B otobüsü nerede?" |
| `SOAP DurakDetay_GYY` (`/ibb/ibb.asmx`) | Rota hesaplamada durak koordinatları |

> ⚠️ `GetTimeTable` endpoint'i broken (sunucu taraflı SOAP hatası).

---

### İSKİ (Su Faturası)
**Base:** `https://esubeapi.iski.gov.tr/EsubeAPI`

| Endpoint | Örnek Soru |
|---|---|
| `POST /tahsilat/uyeliksizTCyeBagliBorclar` | "Su faturamı sorgulamak istiyorum" |

---

### İBB Çözüm Merkezi
**Base:** `https://api.ibb.gov.tr/cagri-merkezi-backend/api`

| Endpoint | İşlem |
|---|---|
| `POST /Soap/newApplication` | Şikayet başvurusu gönderme |
| `POST /Soap/SftpUpload` | Fotoğraf/dosya yükleme |

---

### TKM Trafik
**Base:** `https://api.ibb.gov.tr/tkmservices/api`

| Endpoint | Örnek Soru |
|---|---|
| `GET /TrafficData/v1/TrafficIndexHistory/1/5M` | "Trafik nasıl?" |

---

### Ücretsiz / Açık Kaynak API'ler

| Servis | Kullanım |
|---|---|
| Nominatim (OpenStreetMap) | Adres → koordinat (geocode) + koordinat → adres (reverse) |
| OSRM | Yürüyüş + sürüş rotası polyline hesaplama |

---

## Özellikler

- **Gerçek zamanlı veri:** Metro durumu, otobüs konumu, trafik indeksi canlı API'den
- **Multi-step akışlar:** Fatura ödeme (elektrik/su/doğalgaz) ve şikayet başvurusu adım adım yönlendirme, LLM validasyonlu
- **RAG:** 34.273 İBB kaydı (sağlık tesisi, kent lokantası, park, vb.) üzerinde vektör arama
- **Etkileşimli harita:** Rota (yürüyüş=yeşil, otobüs=mavi, tramvay=kırmızı, metro=mor), konum pini, RAG çoklu pin
- **Konum:** GPS → en yakın durak/istasyon, reverse geocode, rota hesaplama
- **Ses:** Web Speech API ile Türkçe sesli giriş, SpeechSynthesis ile sesli yanıt (rota dahil)
- **Görsel içerik:** Metro haritası ve aktivite görselleri inline chat'te gösterilir
- **LLM fallback:** RAG ve API'lerin yetersiz kaldığı durumlarda gemma3:4b devreye girer

---

## Docker ile Çalıştırma

```bash
docker compose up --build
```

İlk çalıştırmada modeller indirilmemiş olabilir:

```bash
docker exec ibbbot-ollama ollama pull gemma3:4b
docker exec ibbbot-ollama ollama pull embeddinggemma
```

| Servis | Port |
|---|---|
| Frontend (nginx) | 80 |
| Backend (Spring Boot) | 8080 |
| RAG (FastAPI) | 8001 |
| Ollama | 11434 |

---

## Lokal Geliştirme

```bash
# Ollama (0.21.0 gerekli)
ollama pull gemma3:4b
ollama pull embeddinggemma

# Backend
cd backend
mvn spring-boot:run

# RAG servisi
cd python-service
pip install -r requirements.txt
# rag_index.pkl repo'da yok, Colab'da rag.ipynb'yi çalıştırarak üretin
INDEX_PATH="$(pwd)/rag_index.pkl" uvicorn main:app --port 8001

# Frontend
cd frontend
npm install
npm run dev
```

> **Not:** Ollama **0.21.0** sürümü zorunludur. Farklı sürümlerde `embeddinggemma` embedding vektörleri uyumsuz olur ve RAG çalışmaz.
