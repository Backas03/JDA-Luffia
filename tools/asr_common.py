import math
import re
import unicodedata
from urllib.parse import urlsplit

LANGUAGE_NAMES = {
    "zh": "Chinese", "en": "English", "yue": "Cantonese", "ar": "Arabic", "de": "German", "fr": "French",
    "es": "Spanish", "pt": "Portuguese", "id": "Indonesian", "it": "Italian", "ko": "Korean", "ru": "Russian",
    "th": "Thai", "vi": "Vietnamese", "ja": "Japanese", "tr": "Turkish", "hi": "Hindi", "ms": "Malay",
    "nl": "Dutch", "sv": "Swedish", "da": "Danish", "fi": "Finnish", "pl": "Polish", "cs": "Czech",
    "fil": "Filipino", "fa": "Persian", "el": "Greek", "hu": "Hungarian", "mk": "Macedonian", "ro": "Romanian",
}
LANGUAGE_CODES = {name.lower(): code for code, name in LANGUAGE_NAMES.items()}
ALIGNER_LANGUAGES = {
    "Chinese", "English", "Cantonese", "French", "German", "Italian", "Japanese", "Korean", "Portuguese",
    "Russian", "Spanish",
}
SENTENCE_END = ".!?。！？"
TRANSCRIPTION_PATH = "/v1/audio/transcriptions"
LRC_TIME_TAG = re.compile(r"[\[<]\d{1,2}:\d{2}(?:[.:]\d{1,3})?[\]>]")
LRC_META_LINE = re.compile(r"^\s*\[[A-Za-z#][^\]:]*:[^\]]*\]\s*$")


def language_name(code):
    if code is None:
        return None
    key = code.strip().lower()
    if not key:
        return None
    if key in LANGUAGE_NAMES:
        return LANGUAGE_NAMES[key]
    if key in LANGUAGE_CODES:
        return key[:1].upper() + key[1:]
    return None


def language_code(name):
    if not name:
        return ""
    first = name.split(",")[0].strip().lower()
    return LANGUAGE_CODES.get(first, first)


def language_hint(text):
    for ch in text:
        if "぀" <= ch <= "ヿ":
            return "ja"
    for ch in text:
        if "가" <= ch <= "힣":
            return "ko"
    return None


def transcription_url(base):
    base = base.strip().rstrip("/")
    if urlsplit(base).path:
        return base
    return base + TRANSCRIPTION_PATH


def is_cjk(ch):
    code = ord(ch)
    return (
        0x1100 <= code <= 0x11FF
        or 0x3000 <= code <= 0x30FF
        or 0x3130 <= code <= 0x318F
        or 0x3400 <= code <= 0x4DBF
        or 0x4E00 <= code <= 0x9FFF
        or 0xAC00 <= code <= 0xD7A3
        or 0xF900 <= code <= 0xFAFF
        or 0xFF00 <= code <= 0xFFEF
    )


def needs_space(previous, current):
    if not previous or not current:
        return False
    return not (is_cjk(previous[-1]) and is_cjk(current[0]))


def join_words(words):
    text = ""
    previous = ""
    for word in words:
        token = word["word"]
        if not token:
            continue
        space = word.get("space_before")
        if space is None:
            space = needs_space(previous, token)
        if text and space:
            text += " "
        text += token
        previous = token
    return text.strip()


def restore_spacing(text, tokens, max_skip=24):
    lowered = text.lower()
    cursor = 0
    restored = []
    for token in tokens:
        key = token.lower()
        index = lowered.find(key, cursor) if key else -1
        if index < 0 or index - cursor > max_skip:
            restored.append({"word": token, "space_before": None})
            continue
        between = text[cursor:index]
        gap = between.strip()
        if gap and restored and not any(ch.isalnum() for ch in gap):
            restored[-1]["word"] += gap
        restored.append({"word": text[index:index + len(token)], "space_before": bool(restored) and any(ch.isspace() for ch in between)})
        cursor = index + len(token)
    tail = text[cursor:].strip()
    if tail and restored and not any(ch.isalnum() for ch in tail):
        restored[-1]["word"] += tail
    return restored


def is_boundary(previous, current):
    if current.get("space_before"):
        return True
    tail = previous["word"].rstrip()[-1:]
    return tail in SENTENCE_END + ",、"


def largest_gap_index(words):
    candidates = [i for i in range(1, len(words)) if is_boundary(words[i - 1], words[i])]
    if not candidates:
        candidates = list(range(1, len(words)))
    return max(candidates, key=lambda i: (words[i]["start"] - words[i - 1]["end"], -abs(i - len(words) / 2)))


def split_capped(words, max_seconds, max_chars):
    if len(words) < 2:
        return [words]
    duration = words[-1]["end"] - words[0]["start"]
    length = len(join_words(words).replace(" ", ""))
    if duration <= max_seconds and length <= max_chars:
        return [words]
    cut = largest_gap_index(words)
    return split_capped(words[:cut], max_seconds, max_chars) + split_capped(words[cut:], max_seconds, max_chars)


def text_length(words):
    return len(join_words(words).replace(" ", ""))


def merge_short(pieces, min_chars):
    merged = []
    for piece in pieces:
        if merged and text_length(merged[-1]) < min_chars:
            merged[-1] = merged[-1] + piece
        elif merged and text_length(piece) < min_chars:
            merged[-1] = merged[-1] + piece
        else:
            merged.append(piece)
    return merged


def build_segments(words, max_gap=1.5, max_seconds=10.0, max_chars=40, min_chars=3):
    groups = []
    current = []
    for word in words:
        if current:
            gap = word["start"] - current[-1]["end"]
            ended = current[-1]["word"].rstrip()[-1:] in SENTENCE_END
            if gap >= max_gap or ended:
                groups.append(current)
                current = []
        current.append(word)
    if current:
        groups.append(current)
    pieces = []
    for group in groups:
        pieces.extend(split_capped(group, max_seconds, max_chars))
    segments = []
    for piece in merge_short(pieces, min_chars):
        text = join_words(piece)
        if not text:
            continue
        segments.append({"start": piece[0]["start"], "end": piece[-1]["end"], "text": text, "words": piece})
    return segments


def script_language(text):
    kana = hangul = han = latin = 0
    for ch in text:
        code = ord(ch)
        if 0x3040 <= code <= 0x30FF:
            kana += 1
        elif 0xAC00 <= code <= 0xD7A3 or 0x1100 <= code <= 0x11FF or 0x3130 <= code <= 0x318F:
            hangul += 1
        elif 0x4E00 <= code <= 0x9FFF or 0x3400 <= code <= 0x4DBF:
            han += 1
        elif ch.isalpha() and code < 0x0250:
            latin += 1
    if kana:
        return "Japanese"
    if hangul:
        return "Korean"
    if han:
        return "Chinese"
    if latin:
        return "English"
    return None


def script_weight(text):
    return sum(2 if is_cjk(ch) else 1 for ch in text if ch.isalnum())


def majority_language(texts):
    weights = {}
    for text in texts:
        language = script_language(text or "")
        if language:
            weights[language] = weights.get(language, 0) + script_weight(text)
    if not weights:
        return None
    return max(weights, key=lambda key: (weights[key], -list(weights).index(key)))


def stray_indices(texts, majority):
    return [i for i, text in enumerate(texts) if script_language(text or "") not in (None, majority)]


def offset_words(words, offset):
    return [{**word, "start": word["start"] + offset, "end": word["end"] + offset} for word in words]


def offset_segments(segments, offset):
    return [
        {**segment, "start": segment["start"] + offset, "end": segment["end"] + offset,
         "words": offset_words(segment.get("words", []), offset)}
        for segment in segments
    ]


def percentile(values, ratio):
    ordered = sorted(values)
    if not ordered:
        return 0.0
    return ordered[min(len(ordered) - 1, int(ratio * len(ordered)))]


def active_intervals(energy, frame_seconds, threshold):
    intervals = []
    start = None
    for i, value in enumerate(energy):
        if value >= threshold:
            if start is None:
                start = i
        elif start is not None:
            intervals.append((start * frame_seconds, i * frame_seconds))
            start = None
    if start is not None:
        intervals.append((start * frame_seconds, len(energy) * frame_seconds))
    return intervals


def merge_intervals(intervals, max_gap):
    merged = []
    for start, end in intervals:
        if merged and start - merged[-1][1] <= max_gap:
            merged[-1] = (merged[-1][0], max(merged[-1][1], end))
        else:
            merged.append((start, end))
    return merged


def vocal_regions(energy, frame_seconds, threshold_ratio=0.1, merge_gap=0.6, min_region=0.3, pad=0.25, total_seconds=None):
    if total_seconds is None:
        total_seconds = len(energy) * frame_seconds
    reference = percentile(energy, 0.95)
    if reference <= 0:
        return []
    intervals = active_intervals(energy, frame_seconds, reference * threshold_ratio)
    merged = [(s, e) for s, e in merge_intervals(intervals, merge_gap) if e - s >= min_region]
    padded = [(max(0.0, s - pad), min(total_seconds, e + pad)) for s, e in merged]
    return merge_intervals(padded, 0.0)


def quietest_point(energy, frame_seconds, low, high):
    first = int(low / frame_seconds)
    last = int(high / frame_seconds)
    if last <= first or first >= len(energy):
        return (low + high) / 2
    last = min(last, len(energy))
    best = min(range(first, last), key=lambda i: energy[i])
    return (best + 0.5) * frame_seconds


def split_region(start, end, energy, frame_seconds, max_seconds, search=5.0):
    duration = end - start
    pieces = math.ceil(duration / max_seconds) if max_seconds > 0 else 1
    if pieces <= 1:
        return [(start, end)]
    margin = min(search, duration / pieces / 4)
    bounds = [start]
    for i in range(1, pieces):
        target = start + duration * i / pieces
        low = max(bounds[-1] + margin, target - search)
        high = min(end - margin, target + search)
        cut = quietest_point(energy, frame_seconds, low, high) if low < high else target
        bounds.append(min(max(cut, bounds[-1] + margin), end - margin))
    bounds.append(end)
    return list(zip(bounds, bounds[1:]))


def chunk_regions(regions, energy, frame_seconds, max_seconds):
    chunks = []
    for start, end in regions:
        chunks.extend(split_region(start, end, energy, frame_seconds, max_seconds))
    return chunks


def frame_energy(samples, sample_rate, frame_ms=50):
    import numpy as np

    frame = max(1, int(sample_rate * frame_ms / 1000))
    data = np.asarray(samples, dtype=np.float32)
    count = len(data) // frame
    if count == 0:
        return [float(np.sqrt(np.mean(data ** 2)))] if len(data) else [], frame / sample_rate
    squared = data[: count * frame].reshape(count, frame) ** 2
    return np.sqrt(squared.mean(axis=1)).tolist(), frame / sample_rate


def rounded(value):
    return round(float(value), 3)


def verbose_json(language, duration, segments):
    words = []
    parts = []
    texts = []
    for segment in segments:
        parts.append({"start": rounded(segment["start"]), "end": rounded(segment["end"]), "text": segment["text"]})
        texts.append(segment["text"])
        for word in segment.get("words", []):
            words.append({"word": word["word"], "start": rounded(word["start"]), "end": rounded(word["end"])})
    return {
        "task": "transcribe",
        "language": language_code(language),
        "duration": rounded(duration),
        "text": join_words([{"word": text} for text in texts]),
        "segments": parts,
        "words": words,
    }


def strip_lrc(text):
    lines = []
    for line in text.splitlines():
        if LRC_META_LINE.match(line):
            continue
        cleaned = LRC_TIME_TAG.sub("", line).strip()
        if cleaned:
            lines.append(cleaned)
    return "\n".join(lines)


def normalize_text(text):
    folded = unicodedata.normalize("NFKC", text).lower()
    kept = "".join(ch if ch.isalnum() else " " for ch in folded)
    return " ".join(kept.split())


def levenshtein(source, target):
    if source == target:
        return 0
    if not source:
        return len(target)
    if not target:
        return len(source)
    previous = list(range(len(target) + 1))
    for i, s in enumerate(source, 1):
        current = [i]
        for j, t in enumerate(target, 1):
            current.append(min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (s != t)))
        previous = current
    return previous[-1]


def cer(reference, hypothesis):
    ref = normalize_text(reference).replace(" ", "")
    hyp = normalize_text(hypothesis).replace(" ", "")
    if not ref:
        return None
    return levenshtein(ref, hyp) / len(ref)


def wer(reference, hypothesis):
    ref = normalize_text(reference).split()
    hyp = normalize_text(hypothesis).split()
    if not ref:
        return None
    return levenshtein(ref, hyp) / len(ref)
