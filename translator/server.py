import os
import re
from typing import List

import ctranslate2
from fastapi import FastAPI
from pydantic import BaseModel
from transformers import AutoTokenizer

MODEL_DIR = os.environ.get("NLLB_MODEL_DIR", os.path.join(os.path.dirname(__file__), "models", "nllb-200-distilled-600M-int8"))
TOKENIZER_NAME = os.environ.get("NLLB_TOKENIZER", "facebook/nllb-200-distilled-600M")
TARGET_DEFAULT = "kor_Hang"
BEAM_SIZE = int(os.environ.get("NLLB_BEAM_SIZE", "2"))
MAX_BATCH = int(os.environ.get("NLLB_MAX_BATCH", "16"))

HANGUL = re.compile(r"[가-힣ᄀ-ᇿ㄰-㆏]")
KANA = re.compile(r"[぀-ヿ]")
HAN = re.compile(r"[一-鿿]")
LATIN = re.compile(r"[A-Za-z]")
CYRILLIC = re.compile(r"[Ѐ-ӿ]")

app = FastAPI()
translator = ctranslate2.Translator(
    MODEL_DIR,
    device="cpu",
    compute_type="int8",
    inter_threads=1,
    intra_threads=max(1, (os.cpu_count() or 2) - 1),
)
tokenizer = AutoTokenizer.from_pretrained(TOKENIZER_NAME)


class TranslateRequest(BaseModel):
    lines: List[str]
    src: str = "auto"
    tgt: str = TARGET_DEFAULT


class TranslateResponse(BaseModel):
    lines: List[str]
    src: str


def detect_language(lines: List[str]) -> str:
    text = "\n".join(lines)
    counts = {
        "kor_Hang": len(HANGUL.findall(text)),
        "jpn_Jpan": len(KANA.findall(text)),
        "zho_Hans": len(HAN.findall(text)),
        "eng_Latn": len(LATIN.findall(text)),
        "rus_Cyrl": len(CYRILLIC.findall(text)),
    }
    if counts["jpn_Jpan"] > 0 and counts["jpn_Jpan"] * 4 >= counts["zho_Hans"]:
        counts["jpn_Jpan"] += counts["zho_Hans"]
        counts["zho_Hans"] = 0
    best = max(counts, key=counts.get)
    return best if counts[best] > 0 else "eng_Latn"


def is_translatable(line: str) -> bool:
    stripped = line.strip()
    if not stripped:
        return False
    return bool(KANA.search(stripped) or HAN.search(stripped) or LATIN.search(stripped) or CYRILLIC.search(stripped))


@app.get("/health")
def health():
    return {"status": "ok", "model": MODEL_DIR}


def line_language(line: str, fallback: str) -> str:
    if HANGUL.search(line):
        return "kor_Hang"
    if KANA.search(line):
        return "jpn_Jpan"
    if CYRILLIC.search(line):
        return "rus_Cyrl"
    han = len(HAN.findall(line))
    latin = len(LATIN.findall(line))
    if han and not latin:
        return fallback if fallback in ("jpn_Jpan", "zho_Hans") else "zho_Hans"
    if latin and not han:
        return "eng_Latn"
    return fallback


def translate_group(src: str, tgt: str, lines: List[str]) -> List[str]:
    tokenizer.src_lang = src
    sources = [tokenizer.convert_ids_to_tokens(tokenizer.encode(line.strip())) for line in lines]
    results = translator.translate_batch(
        sources,
        target_prefix=[[tgt]] * len(sources),
        beam_size=BEAM_SIZE,
        max_batch_size=MAX_BATCH,
        max_decoding_length=128,
    )
    outputs = []
    for result in results:
        tokens = result.hypotheses[0][1:]
        outputs.append(tokenizer.decode(tokenizer.convert_tokens_to_ids(tokens), skip_special_tokens=True).strip())
    return outputs


@app.post("/translate", response_model=TranslateResponse)
def translate(req: TranslateRequest):
    batch_language = detect_language(req.lines) if req.src == "auto" else req.src
    outputs = list(req.lines)
    groups = {}
    for i, line in enumerate(req.lines):
        if not is_translatable(line):
            continue
        language = line_language(line, batch_language) if req.src == "auto" else req.src
        if language == req.tgt:
            continue
        groups.setdefault(language, []).append(i)
    for language, indices in groups.items():
        translated = translate_group(language, req.tgt, [req.lines[i] for i in indices])
        for i, text in zip(indices, translated):
            outputs[i] = text
    return TranslateResponse(lines=outputs, src=batch_language)
