import argparse
import base64
import io
import json
import logging
import os
import subprocess
import sys
import tempfile
import threading
import urllib.error
import urllib.request
import uuid
import wave
from array import array
from dataclasses import dataclass

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from asr_common import (
    LANGUAGE_NAMES,
    build_segments,
    chunk_regions,
    frame_energy,
    language_name,
    majority_language,
    offset_segments,
    stray_indices,
    restore_spacing,
    script_language,
    verbose_json,
    vocal_regions,
)

SAMPLE_RATE = 16000
SEPARATE_MODES = ("off", "input", "vad")
BACKENDS = ("transformers", "vllm", "llamacpp")
ASR_TEXT_TAG = "<asr_text>"
LANGUAGE_PREFIX = "language "
LOGGER = logging.getLogger("qwen_asr_server")


class AudioError(Exception):
    pass


class BackendError(Exception):
    pass


@dataclass
class Settings:
    model: str
    aligner: str
    align: bool
    backend: str
    device: str
    attn: str
    dtype: str
    host: str
    port: int
    separate: str
    separator_model: str
    separator_dir: str | None
    separator_overlap: int
    separator_batch: int
    chunk_seconds: float
    vad_threshold: float
    max_new_tokens: int
    batch_size: int
    gpu_memory_utilization: float
    llama_url: str
    llama_timeout: float
    work_dir: str


def env(name, default):
    return os.environ.get("QWEN_ASR_" + name, default)


def flag(value):
    return str(value).strip().lower() in ("1", "true", "yes", "on")


def load_settings(argv=None):
    parser = argparse.ArgumentParser(description="Qwen3-ASR sidecar speaking the whisper /v1/audio/transcriptions contract")
    parser.add_argument("--model", default=env("MODEL", "Qwen/Qwen3-ASR-1.7B"))
    parser.add_argument("--aligner", default=env("ALIGNER", "Qwen/Qwen3-ForcedAligner-0.6B"))
    parser.add_argument("--align", default=env("ALIGN", "1"))
    parser.add_argument("--backend", default=env("BACKEND", "transformers"), choices=BACKENDS)
    parser.add_argument("--device", default=env("DEVICE", "cuda"))
    parser.add_argument("--attn", default=env("ATTN", "sdpa"))
    parser.add_argument("--dtype", default=env("DTYPE", ""))
    parser.add_argument("--host", default=env("HOST", "0.0.0.0"))
    parser.add_argument("--port", type=int, default=int(env("PORT", "8000")))
    parser.add_argument("--separate", default=env("SEPARATE", "off"), choices=SEPARATE_MODES)
    parser.add_argument("--separator-model", default=env("SEPARATOR_MODEL", "htdemucs.yaml"))
    parser.add_argument("--separator-dir", default=env("SEPARATOR_DIR", "") or None)
    parser.add_argument("--separator-overlap", type=int, default=int(env("SEPARATOR_OVERLAP", "2")))
    parser.add_argument("--separator-batch", type=int, default=int(env("SEPARATOR_BATCH", "4")))
    parser.add_argument("--chunk-seconds", type=float, default=float(env("CHUNK_SECONDS", "30")))
    parser.add_argument("--vad-threshold", type=float, default=float(env("VAD_THRESHOLD", "0.1")))
    parser.add_argument("--max-new-tokens", type=int, default=int(env("MAX_NEW_TOKENS", "512")))
    parser.add_argument("--batch-size", type=int, default=int(env("BATCH_SIZE", "8")))
    parser.add_argument("--gpu-memory-utilization", type=float, default=float(env("GPU_MEMORY_UTILIZATION", "0.7")))
    parser.add_argument("--llama-url", default=env("LLAMA_URL", "http://127.0.0.1:8081"))
    parser.add_argument("--llama-timeout", type=float, default=float(env("LLAMA_TIMEOUT", "300")))
    parser.add_argument("--work-dir", default=env("WORK_DIR", os.path.join(tempfile.gettempdir(), "qwen_asr_server")))
    args = parser.parse_args(argv)
    dtype = args.dtype or ("bfloat16" if args.device.startswith("cuda") else "float32")
    return Settings(
        model=args.model, aligner=args.aligner, align=flag(args.align), backend=args.backend, device=args.device,
        attn=args.attn, dtype=dtype, host=args.host, port=args.port, separate=args.separate,
        separator_model=args.separator_model, separator_dir=args.separator_dir, separator_overlap=args.separator_overlap,
        separator_batch=args.separator_batch, chunk_seconds=args.chunk_seconds,
        vad_threshold=args.vad_threshold, max_new_tokens=args.max_new_tokens, batch_size=args.batch_size,
        gpu_memory_utilization=args.gpu_memory_utilization, llama_url=args.llama_url.rstrip("/"),
        llama_timeout=args.llama_timeout, work_dir=args.work_dir,
    )


def decode_with_ffmpeg(data):
    import numpy as np

    command = ["ffmpeg", "-v", "error", "-i", "pipe:0", "-f", "f32le", "-ac", "1", "-ar", str(SAMPLE_RATE), "pipe:1"]
    try:
        completed = subprocess.run(command, input=data, capture_output=True, check=True)
    except FileNotFoundError:
        raise AudioError("could not decode the audio and ffmpeg is not on PATH")
    except subprocess.CalledProcessError as e:
        raise AudioError("ffmpeg failed: " + e.stderr.decode(errors="replace").strip()[:200])
    return np.frombuffer(completed.stdout, dtype=np.float32).copy(), SAMPLE_RATE


def resample(mono, rate):
    import numpy as np

    mono = np.asarray(mono, dtype=np.float32)
    if rate == SAMPLE_RATE:
        return mono
    import librosa

    return librosa.resample(mono, orig_sr=rate, target_sr=SAMPLE_RATE).astype(np.float32)


def decode_audio(data):
    if not data:
        raise AudioError("empty upload")
    try:
        import soundfile

        samples, rate = soundfile.read(io.BytesIO(data), dtype="float32", always_2d=True)
        mono = samples.mean(axis=1)
    except Exception:
        mono, rate = decode_with_ffmpeg(data)
    mono = resample(mono, rate)
    if len(mono) == 0:
        raise AudioError("audio has no samples")
    return mono


def alignment_items(stamps, chunk_seconds):
    if stamps is None:
        return []
    items = getattr(stamps, "items", None)
    if items is None or callable(items):
        items = stamps
    parsed = []
    for item in items:
        if isinstance(item, dict):
            text, start, end = item.get("text"), item.get("start_time"), item.get("end_time")
        else:
            text, start, end = getattr(item, "text", None), getattr(item, "start_time", None), getattr(item, "end_time", None)
        if text is None or start is None or end is None:
            continue
        parsed.append({"word": str(text).strip(), "start": float(start), "end": float(end)})
    if parsed and max(item["end"] for item in parsed) > chunk_seconds * 3 + 5:
        parsed = [{**item, "start": item["start"] / 1000, "end": item["end"] / 1000} for item in parsed]
    return [item for item in parsed if item["word"]]


def chunk_segments(text, items, chunk_seconds):
    if not items:
        return [{"start": 0.0, "end": chunk_seconds, "text": text, "words": []}]
    tokens = restore_spacing(text, [item["word"] for item in items])
    words = []
    for token, item in zip(tokens, items):
        start = item["start"]
        end = max(start, item["end"])
        words.append({"word": token["word"], "space_before": token["space_before"], "start": start, "end": end})
    return build_segments(words)


def wav_bytes(samples, rate=SAMPLE_RATE):
    if hasattr(samples, "astype"):
        import numpy as np

        pcm = (np.clip(samples, -1.0, 1.0) * 32767).astype("<i2").tobytes()
    else:
        values = array("h", (int(max(-1.0, min(1.0, float(s))) * 32767) for s in samples))
        if sys.byteorder != "little":
            values.byteswap()
        pcm = values.tobytes()
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(rate)
        handle.writeframes(pcm)
    return buffer.getvalue()


def language_prefill(name):
    return LANGUAGE_PREFIX + name + ASR_TEXT_TAG


def llama_request(audio, name, prompt, max_tokens):
    encoded = base64.b64encode(audio).decode("ascii")
    messages = [
        {"role": "system", "content": prompt or ""},
        {"role": "user", "content": [{"type": "input_audio", "input_audio": {"data": encoded, "format": "wav"}}]},
    ]
    if name:
        messages.append({"role": "assistant", "content": language_prefill(name)})
    return {"messages": messages, "temperature": 0, "max_tokens": max_tokens, "stream": False}


def parse_asr_output(raw, name=None):
    content = (raw or "").strip()
    meta, tag, text = content.partition(ASR_TEXT_TAG)
    if not tag:
        return name or "", content
    if name:
        return name, text.strip()
    detected = ""
    for line in meta.splitlines():
        line = line.strip()
        if line.lower().startswith(LANGUAGE_PREFIX):
            detected = line[len(LANGUAGE_PREFIX):].strip()
            break
    if detected.lower() == "none":
        detected = ""
    if detected:
        detected = detected[:1].upper() + detected[1:].lower()
    return detected, text.strip()


def completion_text(payload):
    choices = payload.get("choices") if isinstance(payload, dict) else None
    if not choices:
        raise BackendError("llama-server returned no choices: " + json.dumps(payload, ensure_ascii=False)[:300])
    message = choices[0].get("message") or {}
    return message.get("content") or ""


def http_json(url, body, timeout):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"} if data else {}
    request = urllib.request.Request(url, data=data, headers=headers, method="POST" if data else "GET")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raise BackendError(f"llama-server {e.code} from {url}: {e.read().decode('utf-8', 'replace')[:300]}")
    except (urllib.error.URLError, OSError, ValueError) as e:
        raise BackendError(f"llama-server unreachable at {url}: {e}")


def llama_health(base, timeout=3.0):
    info = {"url": base, "reachable": False}
    try:
        payload = http_json(base + "/health", None, timeout)
        info["reachable"] = True
        info["status"] = payload.get("status")
        props = http_json(base + "/props", None, timeout)
        info["model"] = os.path.basename(props.get("model_path") or "") or None
    except BackendError as e:
        info["error"] = str(e)
    return info


@dataclass
class Transcription:
    language: str
    text: str
    time_stamps: object = None


class LlamaBackend:
    def __init__(self, settings, aligner):
        self.settings = settings
        self.aligner = aligner
        self.model = aligner
        self.url = settings.llama_url + "/v1/chat/completions"

    def transcribe(self, audio, context, language, return_time_stamps):
        results = []
        for samples, rate in audio:
            body = llama_request(wav_bytes(samples, rate), language, context, self.settings.max_new_tokens)
            raw = completion_text(http_json(self.url, body, self.settings.llama_timeout))
            detected, text = parse_asr_output(raw, language)
            results.append(Transcription(detected, text))
        if return_time_stamps and self.aligner is not None:
            self.align(audio, results)
        return results

    def align(self, audio, results):
        pending = [i for i, result in enumerate(results) if result.text]
        size = max(1, self.settings.batch_size)
        for start in range(0, len(pending), size):
            batch = pending[start:start + size]
            aligned = self.aligner.align(
                audio=[audio[i] for i in batch],
                text=[results[i].text for i in batch],
                language=[results[i].language or script_language(results[i].text) or "English" for i in batch],
            )
            for i, stamps in zip(batch, aligned):
                results[i].time_stamps = stamps


def load_aligner(settings):
    import torch
    from qwen_asr.inference.qwen3_forced_aligner import Qwen3ForcedAligner

    return Qwen3ForcedAligner.from_pretrained(
        settings.aligner, dtype=getattr(torch, settings.dtype), device_map=settings.device, attn_implementation=settings.attn,
    )


def load_model(settings):
    if settings.backend == "llamacpp":
        return LlamaBackend(settings, load_aligner(settings) if settings.align else None)
    import torch
    from qwen_asr import Qwen3ASRModel

    dtype = getattr(torch, settings.dtype)
    aligner = settings.aligner if settings.align else None
    aligner_kwargs = {"dtype": dtype, "device_map": settings.device, "attn_implementation": settings.attn} if aligner else None
    if settings.backend == "vllm":
        return Qwen3ASRModel.LLM(
            model=settings.model,
            gpu_memory_utilization=settings.gpu_memory_utilization,
            max_inference_batch_size=settings.batch_size,
            max_new_tokens=settings.max_new_tokens,
            forced_aligner=aligner,
            forced_aligner_kwargs=aligner_kwargs,
        )
    return Qwen3ASRModel.from_pretrained(
        settings.model,
        dtype=dtype,
        device_map=settings.device,
        attn_implementation=settings.attn,
        max_inference_batch_size=settings.batch_size,
        max_new_tokens=settings.max_new_tokens,
        forced_aligner=aligner,
        forced_aligner_kwargs=aligner_kwargs,
    )


def release_gpu_cache():
    import torch

    if torch.cuda.is_available():
        torch.cuda.empty_cache()


def load_separator(settings):
    from audio_separator.separator import Separator

    os.makedirs(settings.work_dir, exist_ok=True)
    kwargs = {
        "log_level": logging.WARNING,
        "output_dir": settings.work_dir,
        "output_format": "WAV",
        "output_single_stem": "Vocals",
        "use_autocast": settings.device.startswith("cuda"),
        "mdxc_params": {"segment_size": 256, "override_model_segment_size": False, "batch_size": settings.separator_batch,
                        "overlap": settings.separator_overlap, "pitch_shift": 0},
        "demucs_params": {"segment_size": "Default", "shifts": 1, "overlap": 0.25, "segments_enabled": True},
    }
    if settings.separator_dir:
        kwargs["model_file_dir"] = settings.separator_dir
    separator = Separator(**kwargs)
    separator.load_model(model_filename=settings.separator_model)
    return separator


class Engine:
    def __init__(self, settings):
        self.settings = settings
        self.lock = threading.Lock()
        target = settings.llama_url if settings.backend == "llamacpp" else settings.model
        LOGGER.info("loading %s (%s, %s, aligner=%s)", target, settings.backend, settings.device, settings.aligner if settings.align else "off")
        self.model = load_model(settings)
        if settings.backend == "llamacpp":
            health = llama_health(settings.llama_url)
            if health["reachable"]:
                LOGGER.info("llama-server at %s is up (%s)", settings.llama_url, health.get("model") or health.get("status"))
            else:
                LOGGER.warning("llama-server not reachable yet: %s", health.get("error"))
        self.separator = load_separator(settings) if settings.separate != "off" else None
        if self.separator is not None:
            LOGGER.info("separator %s ready (mode=%s)", settings.separator_model, settings.separate)

    def transcribe(self, data, filename, language, prompt):
        name = None
        if language and language.strip():
            name = language_name(language)
            if name is None:
                raise AudioError("unsupported language: " + language)
        mix = decode_audio(data)
        duration = len(mix) / SAMPLE_RATE
        with self.lock:
            source, chunks = self.plan(data, filename, mix)
            segments, languages = self.run(source, chunks, name, prompt)
        detected = name or next((lang for lang in languages if lang), "")
        return verbose_json(detected, duration, segments)

    def plan(self, data, filename, mix):
        import numpy as np

        mode = self.settings.separate
        total = len(mix) / SAMPLE_RATE
        if mode == "off":
            source = mix
            energy_source = mix
        else:
            vocals = self.separate(data, filename, len(mix))
            source = vocals if mode == "input" else mix
            energy_source = vocals
        energy, frame = frame_energy(energy_source, SAMPLE_RATE)
        if mode == "vad":
            regions = vocal_regions(energy, frame, threshold_ratio=self.settings.vad_threshold, total_seconds=total)
        else:
            regions = [(0.0, total)]
        if self.settings.chunk_seconds > 0:
            chunks = chunk_regions(regions, energy, frame, self.settings.chunk_seconds)
        else:
            chunks = regions
        return np.asarray(source, dtype=np.float32), chunks

    def run(self, source, chunks, name, prompt):
        if not chunks:
            return [], []
        clips = [(source[int(start * SAMPLE_RATE):int(end * SAMPLE_RATE)], SAMPLE_RATE) for start, end in chunks]
        results = list(self.model.transcribe(audio=clips, context=prompt or "", language=name, return_time_stamps=self.settings.align))
        if name is None:
            results = self.pin_language(clips, results, prompt)
        segments = []
        languages = []
        for (start, end), result in zip(chunks, results):
            text = (getattr(result, "text", "") or "").strip()
            if not text:
                continue
            languages.append(getattr(result, "language", "") or "")
            items = alignment_items(getattr(result, "time_stamps", None), end - start) if self.settings.align else []
            segments.extend(offset_segments(chunk_segments(text, items, end - start), start))
        return segments, languages

    def pin_language(self, clips, results, prompt):
        texts = [getattr(result, "text", "") or "" for result in results]
        majority = majority_language(texts)
        if majority is None or majority not in LANGUAGE_NAMES.values():
            return results
        strays = stray_indices(texts, majority)
        if not strays:
            return results
        LOGGER.info("pinning language to %s and redoing %d of %d chunk(s)", majority, len(strays), len(results))
        redone = self.model.transcribe(audio=[clips[i] for i in strays], context=prompt or "", language=majority, return_time_stamps=self.settings.align)
        for i, result in zip(strays, redone):
            results[i] = result
        return results

    def separate(self, data, filename, length):
        import numpy as np

        work = self.settings.work_dir
        os.makedirs(work, exist_ok=True)
        uid = uuid.uuid4().hex
        extension = os.path.splitext(filename or "")[1] or ".wav"
        source = os.path.join(work, uid + extension)
        outputs = []
        try:
            with open(source, "wb") as handle:
                handle.write(data)
            outputs = self.separator.separate(source, custom_output_names={"Vocals": uid + "_vocals"})
            if not outputs:
                raise AudioError("separator produced no vocals stem")
            path = outputs[0] if os.path.isabs(outputs[0]) else os.path.join(work, outputs[0])
            with open(path, "rb") as handle:
                vocals = decode_audio(handle.read())
        finally:
            for path in [source] + [p if os.path.isabs(p) else os.path.join(work, p) for p in outputs]:
                try:
                    os.remove(path)
                except OSError:
                    pass
            release_gpu_cache()
        if len(vocals) < length:
            vocals = np.pad(vocals, (0, length - len(vocals)))
        return vocals[:length]


def model_device(model):
    candidate = model
    for _ in range(3):
        if candidate is None:
            break
        parameters = getattr(candidate, "parameters", None)
        if callable(parameters):
            try:
                return str(next(parameters()).device)
            except (StopIteration, TypeError):
                pass
        candidate = getattr(candidate, "model", None)
    return None


def device_info(engine):
    import torch

    info = {"requested": engine.settings.device, "cuda_available": torch.cuda.is_available()}
    if torch.cuda.is_available():
        info["gpu"] = torch.cuda.get_device_name(0)
        info["vram_used_mb"] = round(torch.cuda.memory_allocated() / 1024 / 1024)
    placed = model_device(engine.model)
    if placed is not None:
        info["model_on"] = placed
    return info


def create_app(engine, settings):
    from fastapi import FastAPI, File, Form, UploadFile
    from fastapi.responses import JSONResponse

    app = FastAPI()

    @app.get("/health")
    def health():
        info = {
            "status": "ok",
            "model": settings.model,
            "aligner": settings.aligner if settings.align else None,
            "backend": settings.backend,
            "separate": settings.separate,
            "device": device_info(engine),
        }
        if settings.backend == "llamacpp":
            info["llama"] = llama_health(settings.llama_url)
        return info

    @app.post("/v1/audio/transcriptions")
    def transcribe(file: UploadFile = File(...), language: str | None = Form(None), prompt: str | None = Form(None)):
        data = file.file.read()
        try:
            return engine.transcribe(data, file.filename, language, prompt)
        except AudioError as e:
            return JSONResponse({"error": str(e)}, status_code=400)
        except BackendError as e:
            LOGGER.error("%s", e)
            return JSONResponse({"error": str(e)}, status_code=502)
        except Exception as e:
            LOGGER.exception("transcription failed")
            return JSONResponse({"error": f"{type(e).__name__}: {e}"}, status_code=500)

    return app


def main(argv=None):
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    settings = load_settings(argv)
    try:
        engine = Engine(settings)
    except ImportError as e:
        sys.exit(f"missing dependency: {e}. Install tools/qwen_asr_requirements.txt (and audio-separator for QWEN_ASR_SEPARATE)")
    except Exception as e:
        sys.exit(f"failed to load models: {type(e).__name__}: {e}")
    import uvicorn

    uvicorn.run(create_app(engine, settings), host=settings.host, port=settings.port)


if __name__ == "__main__":
    main()
