#!/bin/bash
cd "$(dirname "$0")"
MODEL_FILE="${MODEL_FILE:-trillionlabs.Tri-7B.Q4_K_M.gguf}"
THREADS="${LLM_THREADS:-6}"
THREADS_BATCH="${LLM_THREADS_BATCH:-12}"
PORT="${TRANSLATOR_PORT:-8765}"
export LD_LIBRARY_PATH="$(pwd)/bin:${LD_LIBRARY_PATH}"
exec nice -n "${LLM_NICE:-10}" bin/llama-server \
  -m "models/${MODEL_FILE}" \
  -a "${MODEL_ALIAS:-Tri-7B}" \
  --host 127.0.0.1 \
  --port "${PORT}" \
  -t "${THREADS}" \
  -tb "${THREADS_BATCH}" \
  -c 8192 \
  -np 1 \
  --cache-ram "${LLM_CACHE_RAM:-0}" \
  --no-webui
