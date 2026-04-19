import json
import math
import os
import pickle
import re
from contextlib import asynccontextmanager
from dataclasses import dataclass
from typing import Any, Dict, List, Tuple

import requests
from fastapi import FastAPI
from pydantic import BaseModel

OLLAMA_URL  = os.getenv("OLLAMA_BASE_URL", "http://localhost:11434")
EMBED_MODEL = os.getenv("EMBED_MODEL", "embeddinggemma")
CHAT_MODEL  = os.getenv("CHAT_MODEL",  "gemma3:4b")
INDEX_PATH  = os.getenv("INDEX_PATH",  "/app/rag_index.pkl")

TOP_K_CANDIDATES = 80
TOP_K_CONTEXT    = 8
EMBED_BATCH_SIZE = 64
RELEVANCE_THRESHOLD = 0.30
ANSWER_THRESHOLD    = 0.35
NOT_FOUND = "Bu veri setinde bulamadım."


@dataclass
class SearchHit:
    score: float
    record: Dict[str, Any]


# ── Ollama client ─────────────────────────────────────────────────────────────

def embed_one(text: str) -> List[float]:
    resp = requests.post(f"{OLLAMA_URL}/api/embed",
        json={"model": EMBED_MODEL, "input": text, "truncate": True, "keep_alive": "15m"},
        timeout=180)
    resp.raise_for_status()
    return resp.json()["embeddings"][0]


def llm_generate(prompt: str) -> str:
    resp = requests.post(f"{OLLAMA_URL}/api/generate",
        json={"model": CHAT_MODEL, "prompt": prompt, "stream": False,
              "keep_alive": "15m", "options": {"temperature": 0.1, "num_predict": 400}},
        timeout=300)
    resp.raise_for_status()
    return resp.json()["response"]


# ── Scoring helpers ───────────────────────────────────────────────────────────

def _normalize(value: Any) -> str:
    if value is None:
        return ""
    return re.sub(r"\s+", " ", str(value)).strip()


def _tokenize(text: str) -> List[str]:
    text = (_normalize(text).lower()
            .replace("ı","i").replace("İ","i").replace("ş","s")
            .replace("ğ","g").replace("ü","u").replace("ö","o").replace("ç","c"))
    return re.findall(r"[a-zA-Z0-9_]+", text)


def cosine(a: List[float], b: List[float]) -> float:
    if not a or not b or len(a) != len(b):
        return 0.0
    dot = sum(x * y for x, y in zip(a, b))
    na  = math.sqrt(sum(x * x for x in a))
    nb  = math.sqrt(sum(y * y for y in b))
    return dot / (na * nb) if na and nb else 0.0


def keyword_score(query: str, rec: Dict[str, Any]) -> float:
    q_tokens = set(_tokenize(query))
    if not q_tokens:
        return 0.0
    haystack = set()
    for f in ["name", "dataset", "category", "district", "neighborhood", "address"]:
        haystack.update(_tokenize(rec.get(f, "")))
    haystack.update(_tokenize(" ".join(rec.get("keywords", []) or [])))
    return min(len(q_tokens & haystack) * 0.08, 0.45)


def meta_boost(query: str, rec: Dict[str, Any]) -> float:
    q = _normalize(query).lower()
    score = 0.0
    for field, weight in [("district",0.20),("neighborhood",0.16),("name",0.22),
                           ("dataset",0.10),("category",0.10)]:
        val = _normalize(rec.get(field)).lower()
        if val and val in q:
            score += weight
    return score


# ── RAG core ──────────────────────────────────────────────────────────────────

records: List[Dict[str, Any]] = []


def load_index() -> None:
    global records
    with open(INDEX_PATH, "rb") as f:
        records = pickle.load(f)
    print(f"RAG index loaded: {len(records)} records")


def search(query: str, top_k: int = TOP_K_CONTEXT) -> List[SearchHit]:
    q_emb = embed_one(query)
    scored = sorted([(cosine(q_emb, r["embedding"]), r) for r in records],
                    key=lambda x: x[0], reverse=True)[:TOP_K_CANDIDATES]
    reranked = []
    for emb_score, rec in scored:
        final = emb_score * 0.72 + keyword_score(query, rec) + meta_boost(query, rec)
        reranked.append(SearchHit(score=final, record=rec))
    reranked.sort(key=lambda x: x.score, reverse=True)
    return [h for h in reranked if h.score >= RELEVANCE_THRESHOLD][:top_k]


def answer(query: str, hits: List[SearchHit]) -> Tuple[str, bool]:
    if not hits or hits[0].score < ANSWER_THRESHOLD:
        return NOT_FOUND, False

    blocks = []
    for i, hit in enumerate(hits, 1):
        rec = hit.record
        blocks.append(json.dumps({
            "sira": i, "score": round(hit.score, 4),
            "dataset": rec.get("dataset"), "category": rec.get("category"),
            "name": rec.get("name"), "district": rec.get("district"),
            "neighborhood": rec.get("neighborhood"), "address": rec.get("address"),
            "latitude": rec.get("latitude"), "longitude": rec.get("longitude"),
            "content": rec.get("content"),
        }, ensure_ascii=False))

    prompt = f"""Sen İstanbul şehir verileri için çalışan bir RAG asistanısın.

Kesin kurallar:
- Sadece aşağıdaki kayıtları kullan.
- Kayıtlarda açıkça olmayan bilgiyi üretme.
- Sonuçlar yetersizse sadece şunu yaz: {NOT_FOUND}
- Cevabı Türkçe ver. Uygunsa madde madde listele.
- Varsa isim, ilçe, mahalle, adres bilgilerini kullan.
- Koordinat (latitude, longitude, sayısal konum) bilgilerini cevaba yazma.

Kullanıcı sorusu: {query}

Bulunan kayıtlar:
{chr(10).join(blocks)}

Cevap:""".strip()

    result = llm_generate(prompt).strip()
    # LLM bazen geçerli cevabın sonuna NOT_FOUND ekliyor, temizle
    result = result.replace(NOT_FOUND, "").strip()
    found = bool(result)
    return (result if found else NOT_FOUND), found


# ── FastAPI ───────────────────────────────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI):
    load_index()
    yield

app = FastAPI(lifespan=lifespan)


class QueryRequest(BaseModel):
    query: str


class Location(BaseModel):
    name: str
    lat: float
    lon: float
    address: str = ""

class QueryResponse(BaseModel):
    answer: str
    found: bool
    locations: list[Location] = []


@app.get("/health")
def health():
    return {"status": "ok", "records": len(records)}


@app.post("/query", response_model=QueryResponse)
def query_endpoint(req: QueryRequest):
    hits = search(req.query)
    ans, found = answer(req.query, hits)

    locations = []
    if found:
        for hit in hits:
            rec = hit.record
            try:
                lat = float(rec.get("latitude") or 0)
                lon = float(rec.get("longitude") or 0)
            except (TypeError, ValueError):
                continue
            if lat == 0 or lon == 0:
                continue
            locations.append(Location(
                name=str(rec.get("name") or ""),
                lat=lat,
                lon=lon,
                address=str(rec.get("address") or ""),
            ))

    return QueryResponse(answer=ans, found=found, locations=locations)
