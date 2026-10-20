import argparse
import json
import os
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
import wave

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from asr_common import cer, language_hint, strip_lrc, transcription_url, wer

AUDIO_EXTENSIONS = (".wav", ".mp3", ".m4a", ".opus", ".ogg", ".flac", ".webm")
SAMPLE_RATE = 16000


def parse_endpoint(value):
    label, separator, url = value.partition("=")
    if not separator or not label.strip() or not url.strip():
        raise argparse.ArgumentTypeError("expected label=url, got " + value)
    return label.strip(), transcription_url(url)


def read_reference(path):
    with open(path, encoding="utf-8-sig") as handle:
        text = handle.read()
    return strip_lrc(text) if path.lower().endswith(".lrc") else text.strip()


def find_reference(directory, stem):
    for extension in (".txt", ".lrc"):
        path = os.path.join(directory, stem + extension)
        if os.path.isfile(path):
            return path
    return None


def collect_songs(directory):
    songs = []
    skipped = []
    for name in sorted(os.listdir(directory)):
        stem, extension = os.path.splitext(name)
        if extension.lower() not in AUDIO_EXTENSIONS:
            continue
        reference = find_reference(directory, stem)
        if reference is None:
            skipped.append(name)
            continue
        songs.append({"stem": stem, "audio": os.path.join(directory, name), "reference": read_reference(reference)})
    return songs, skipped


def song_language(directory, stem, requested):
    if requested != "auto":
        return requested
    path = os.path.join(directory, stem + ".lang")
    if os.path.isfile(path):
        with open(path, encoding="utf-8") as handle:
            code = handle.read().strip()
        if code:
            return code
    return language_hint(stem)


def is_java_wav(path):
    try:
        with wave.open(path, "rb") as handle:
            return handle.getnchannels() == 1 and handle.getsampwidth() == 2 and handle.getframerate() == SAMPLE_RATE and handle.getcomptype() == "NONE"
    except (wave.Error, EOFError, OSError):
        return False


def prepare_wav(path, temp_dir):
    if path.lower().endswith(".wav") and is_java_wav(path):
        with open(path, "rb") as handle:
            return handle.read()
    target = os.path.join(temp_dir, uuid.uuid4().hex + ".wav")
    command = ["ffmpeg", "-v", "error", "-y", "-i", path, "-vn", "-ac", "1", "-ar", str(SAMPLE_RATE), "-acodec", "pcm_s16le", "-f", "wav", target]
    subprocess.run(command, check=True, capture_output=True)
    with open(target, "rb") as handle:
        return handle.read()


def multipart(boundary, wav, language):
    parts = [
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n".encode(),
        wav,
        b"\r\n",
    ]
    parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\n{language or 'auto'}\r\n".encode())
    parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"response_format\"\r\n\r\nverbose_json\r\n".encode())
    parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"max_context\"\r\n\r\n0\r\n".encode())
    parts.append(f"--{boundary}--\r\n".encode())
    return b"".join(parts)


def request(url, wav, language, timeout):
    boundary = "luffia-" + uuid.uuid4().hex
    body = multipart(boundary, wav, language)
    req = urllib.request.Request(url, data=body, method="POST", headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    started = time.perf_counter()
    with urllib.request.urlopen(req, timeout=timeout) as response:
        payload = json.loads(response.read().decode("utf-8"))
    return payload, time.perf_counter() - started


def hypothesis_text(payload):
    text = payload.get("text")
    if isinstance(text, str) and text.strip():
        return text.strip()
    return "\n".join(segment.get("text", "").strip() for segment in payload.get("segments", []) if segment.get("text", "").strip())


def evaluate(song, label, url, language, wav, timeout):
    row = {"song": song["stem"], "endpoint": label, "language": language}
    try:
        payload, latency = request(url, wav, language, timeout)
    except urllib.error.HTTPError as e:
        row["error"] = f"HTTP {e.code}: {e.read().decode('utf-8', 'replace')[:200]}"
        return row
    except (urllib.error.URLError, TimeoutError, OSError, ValueError) as e:
        row["error"] = str(e)
        return row
    hypothesis = hypothesis_text(payload)
    row.update({
        "cer": cer(song["reference"], hypothesis),
        "wer": wer(song["reference"], hypothesis),
        "latency": latency,
        "lines": len(payload.get("segments", [])),
        "hypothesis": hypothesis,
    })
    return row


def format_metric(value):
    return "-" if value is None else f"{value:.3f}"


def print_table(rows):
    print(f"{'song':<32} {'endpoint':<12} {'CER':>7} {'WER':>7} {'sec':>7} {'lines':>5}")
    for row in rows:
        name = row["song"][:32]
        if "error" in row:
            print(f"{name:<32} {row['endpoint']:<12} FAILED {row['error']}")
            continue
        print(f"{name:<32} {row['endpoint']:<12} {format_metric(row['cer']):>7} {format_metric(row['wer']):>7} {row['latency']:>7.1f} {row['lines']:>5}")


def summarize(rows, labels):
    summary = {}
    for label in labels:
        scored = [row for row in rows if row["endpoint"] == label and "error" not in row and row["cer"] is not None]
        failures = sum(1 for row in rows if row["endpoint"] == label and "error" in row)
        if scored:
            summary[label] = {
                "songs": len(scored),
                "failures": failures,
                "cer": sum(row["cer"] for row in scored) / len(scored),
                "wer": sum(row["wer"] for row in scored) / len(scored),
                "latency": sum(row["latency"] for row in scored) / len(scored),
            }
        else:
            summary[label] = {"songs": 0, "failures": failures, "cer": None, "wer": None, "latency": None}
    return summary


def print_summary(summary):
    print()
    print(f"{'endpoint':<12} {'songs':>5} {'fail':>4} {'CER':>7} {'WER':>7} {'sec':>7}")
    for label, stats in summary.items():
        latency = "-" if stats["latency"] is None else f"{stats['latency']:.1f}"
        print(f"{label:<12} {stats['songs']:>5} {stats['failures']:>4} {format_metric(stats['cer']):>7} {format_metric(stats['wer']):>7} {latency:>7}")


def main(argv=None):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass
    parser = argparse.ArgumentParser(description="CER/WER of whisper-compatible transcription endpoints on a folder of songs")
    parser.add_argument("--dir", required=True)
    parser.add_argument("--endpoint", action="append", required=True, type=parse_endpoint, metavar="label=url")
    parser.add_argument("--language", default="auto")
    parser.add_argument("--timeout", type=float, default=600)
    parser.add_argument("--out")
    parser.add_argument("--dump")
    args = parser.parse_args(argv)
    songs, skipped = collect_songs(args.dir)
    for name in skipped:
        print(f"skip {name}: no .txt/.lrc reference")
    if not songs:
        sys.exit("no songs with a reference found")
    labels = [label for label, _ in args.endpoint]
    rows = []
    with tempfile.TemporaryDirectory() as temp_dir:
        for song in songs:
            language = song_language(args.dir, song["stem"], args.language)
            try:
                wav = prepare_wav(song["audio"], temp_dir)
            except (subprocess.CalledProcessError, FileNotFoundError, OSError) as e:
                for label in labels:
                    rows.append({"song": song["stem"], "endpoint": label, "language": language, "error": f"ffmpeg: {e}"})
                continue
            for label, url in args.endpoint:
                row = evaluate(song, label, url, language, wav, args.timeout)
                rows.append(row)
                if args.dump and "hypothesis" in row:
                    os.makedirs(args.dump, exist_ok=True)
                    with open(os.path.join(args.dump, f"{song['stem']}.{label}.txt"), "w", encoding="utf-8") as handle:
                        handle.write(row["hypothesis"] + "\n")
    print_table(rows)
    summary = summarize(rows, labels)
    print_summary(summary)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as handle:
            json.dump({"endpoints": dict(args.endpoint), "songs": rows, "summary": summary}, handle, ensure_ascii=False, indent=2)
        print(f"saved {args.out}")


if __name__ == "__main__":
    main()
