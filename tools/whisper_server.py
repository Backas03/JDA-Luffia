import io
import os
import site

for base in site.getsitepackages():
    for sub in ("nvidia/cublas/bin", "nvidia/cudnn/bin"):
        path = os.path.join(base, sub)
        if os.path.isdir(path):
            os.add_dll_directory(path)

from fastapi import FastAPI, File, Form, UploadFile
from faster_whisper import WhisperModel
import uvicorn

MODEL_NAME = os.environ.get("WHISPER_MODEL", "large-v3-turbo")
PORT = int(os.environ.get("WHISPER_PORT", "8000"))

model = WhisperModel(MODEL_NAME, device="cuda", compute_type="float16")
app = FastAPI()


@app.get("/health")
def health():
    return {"status": "ok", "model": MODEL_NAME}


@app.post("/v1/audio/transcriptions")
def transcribe(file: UploadFile = File(...), language: str | None = Form(None)):
    data = file.file.read()
    segments, info = model.transcribe(
        io.BytesIO(data),
        language=language or None,
        word_timestamps=True,
        beam_size=5,
        vad_filter=False,
        condition_on_previous_text=False,
    )
    words = []
    parts = []
    texts = []
    for segment in segments:
        parts.append({"start": segment.start, "end": segment.end, "text": segment.text})
        texts.append(segment.text)
        for word in segment.words or []:
            words.append({"word": word.word, "start": word.start, "end": word.end, "probability": word.probability})
    return {
        "task": "transcribe",
        "language": info.language,
        "duration": info.duration,
        "text": "".join(texts).strip(),
        "segments": parts,
        "words": words,
    }


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=PORT)
