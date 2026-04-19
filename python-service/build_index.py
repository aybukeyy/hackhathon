"""
Çalıştır: python build_index.py --jsonl all_datasets_combined.jsonl
"""
import argparse
import json
import math
import pickle
import re
from typing import Any, Dict, List

import requests

OLLAMA_URL      = "http://localhost:11434"
EMBED_MODEL     = "embeddinggemma"
EMBED_BATCH     = 32
OUTPUT_PATH     = "rag_index.pkl"


def normalize(value: Any) -> str:
    if value is None:
        return ""
    return re.sub(r"\s+", " ", str(value)).strip()


def embed_batch(texts: List[str]) -> List[List[float]]:
    resp = requests.post(f"{OLLAMA_URL}/api/embed",
        json={"model": EMBED_MODEL, "input": texts, "truncate": True, "keep_alive": "15m"},
        timeout=600)
    resp.raise_for_status()
    return resp.json()["embeddings"]


def build(jsonl_path: str) -> None:
    raw: List[Dict[str, Any]] = []
    texts: List[str] = []

    with open(jsonl_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            rec = json.loads(line)
            content = normalize(rec.get("content"))
            if not content:
                continue
            raw.append({
                "id": rec.get("id"), "dataset": rec.get("dataset"),
                "category": rec.get("category"), "name": rec.get("name"),
                "district": rec.get("district"), "neighborhood": rec.get("neighborhood"),
                "address": rec.get("address"), "latitude": rec.get("latitude"),
                "longitude": rec.get("longitude"), "keywords": rec.get("keywords", []),
                "attributes": rec.get("attributes", {}), "content": content,
            })
            texts.append(content)

    print(f"{len(raw)} kayıt okundu, embedding başlıyor...")

    records: List[Dict[str, Any]] = []
    total = len(raw)
    for start in range(0, total, EMBED_BATCH):
        end = min(start + EMBED_BATCH, total)
        embeddings = embed_batch(texts[start:end])
        for rec, emb in zip(raw[start:end], embeddings):
            rec["embedding"] = emb
            records.append(rec)
        print(f"  {end}/{total}", end="\r", flush=True)

    with open(OUTPUT_PATH, "wb") as f:
        pickle.dump(records, f)

    print(f"\nIndex kaydedildi: {OUTPUT_PATH} ({len(records)} kayıt)")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--jsonl", required=True)
    args = parser.parse_args()
    build(args.jsonl)
