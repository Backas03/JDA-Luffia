#!/bin/bash
set -e
cd "$(dirname "$0")"
LLAMA_BUILD="${LLAMA_BUILD:-b11064}"
LLAMA_ARCHIVE="llama-${LLAMA_BUILD}-bin-ubuntu-x64.tar.gz"
MODEL_URL="${MODEL_URL:-https://huggingface.co/DevQuasar/trillionlabs.Tri-7B-GGUF/resolve/main/trillionlabs.Tri-7B.Q4_K_M.gguf}"
MODEL_FILE="${MODEL_FILE:-trillionlabs.Tri-7B.Q4_K_M.gguf}"

mkdir -p bin models
if [ ! -x bin/llama-server ]; then
  curl -L --retry 5 --retry-delay 3 -o "/tmp/${LLAMA_ARCHIVE}" "https://github.com/ggml-org/llama.cpp/releases/download/${LLAMA_BUILD}/${LLAMA_ARCHIVE}"
  rm -rf /tmp/llama-extract && mkdir -p /tmp/llama-extract
  tar -xzf "/tmp/${LLAMA_ARCHIVE}" -C /tmp/llama-extract
  find /tmp/llama-extract -type f \( -name "llama-server" -o -name "*.so*" \) -exec cp -a {} bin/ \;
  rm -rf "/tmp/${LLAMA_ARCHIVE}" /tmp/llama-extract
  chmod +x bin/llama-server
fi
curl -L -C - --retry 5 --retry-delay 3 -o "models/${MODEL_FILE}" "${MODEL_URL}"
SIZE=$(stat -c %s "models/${MODEL_FILE}")
if [ "$SIZE" -lt 4000000000 ]; then
  echo "model download incomplete (${SIZE} bytes). run setup.sh again to resume."
  exit 1
fi
echo "setup complete. run: ./run.sh"
