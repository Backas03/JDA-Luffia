#!/bin/bash
cd "$(dirname "$0")"
MODEL_FILE="${MODEL_FILE:-trillionlabs.Tri-7B.Q4_K_M.gguf}"
THREADS="${LLM_THREADS:-6}"
PORT="${TRANSLATOR_PORT:-8765}"
export LD_LIBRARY_PATH="$(pwd)/bin:${LD_LIBRARY_PATH}"
exec bin/llama-server \
  -m "models/${MODEL_FILE}" \
  --host 127.0.0.1 \
  --port "${PORT}" \
  -t "${THREADS}" \
  -c 4096 \
  -np 1 \
  --no-webui
