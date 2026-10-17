#!/bin/bash
export PATH="/usr/local/bin:${PATH}"
export OLLAMA_HOST="${OLLAMA_HOST:-0.0.0.0:11434}"
export OLLAMA_CONTEXT_LENGTH="${OLLAMA_CONTEXT_LENGTH:-8192}"
export OLLAMA_KEEP_ALIVE="${OLLAMA_KEEP_ALIVE:--1}"
export OLLAMA_NUM_PARALLEL="${OLLAMA_NUM_PARALLEL:-4}"
export OLLAMA_MAX_LOADED_MODELS="${OLLAMA_MAX_LOADED_MODELS:-1}"
export QWEN_ASR_ENABLED="${QWEN_ASR_ENABLED:-1}"
export QWEN_ASR_DIR="${QWEN_ASR_DIR:-${HOME}/qwen-asr}"
export QWEN_ASR_REPO="${QWEN_ASR_REPO:-https://github.com/Backas03/JDA-Luffia.git}"
export QWEN_ASR_VENV="${QWEN_ASR_VENV:-${QWEN_ASR_DIR}/.venv-qwen}"
export QWEN_ASR_HOST="${QWEN_ASR_HOST:-0.0.0.0}"
export QWEN_ASR_PORT="${QWEN_ASR_PORT:-8178}"
export QWEN_ASR_SEPARATE="${QWEN_ASR_SEPARATE:-off}"
SESSION="${AI_SERVER_TMUX_SESSION:-ai-server}"
SCRIPT="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
OLLAMA_ENV=(OLLAMA_HOST OLLAMA_CONTEXT_LENGTH OLLAMA_KEEP_ALIVE OLLAMA_NUM_PARALLEL OLLAMA_MAX_LOADED_MODELS)
QWEN_ENV=(QWEN_ASR_DIR QWEN_ASR_REPO QWEN_ASR_VENV QWEN_ASR_HOST QWEN_ASR_PORT QWEN_ASR_SEPARATE QWEN_ASR_MODEL QWEN_ASR_ALIGNER QWEN_ASR_ALIGN
  QWEN_ASR_ATTN QWEN_ASR_DEVICE QWEN_ASR_SEPARATOR_MODEL QWEN_ASR_CHUNK_SECONDS QWEN_ASR_VAD_THRESHOLD)

serve() {
  if systemctl is-active --quiet ollama 2>/dev/null; then
    echo "ollama systemd service is running. stop it first: sudo systemctl disable --now ollama"
    return 1
  fi
  ollama serve
}

serve_asr() {
  local server="${QWEN_ASR_DIR}/tools/qwen_asr_server.py"
  if [ ! -f "${server}" ]; then
    echo "${server} not found. fetch only tools/ from the repository:"
    echo "  git clone --depth 1 --filter=blob:none --sparse '${QWEN_ASR_REPO}' '${QWEN_ASR_DIR}' && git -C '${QWEN_ASR_DIR}' sparse-checkout set tools"
    echo "later updates: git -C '${QWEN_ASR_DIR}' pull"
    return 1
  fi
  if [ ! -x "${QWEN_ASR_VENV}/bin/python" ]; then
    echo "${QWEN_ASR_VENV} not found. create it:"
    echo "  curl -LsSf https://astral.sh/uv/install.sh | sh && export PATH=\"\$HOME/.local/bin:\$PATH\" && uv venv --python 3.12 '${QWEN_ASR_VENV}' && uv pip install --python '${QWEN_ASR_VENV}/bin/python' torch --index-url https://download.pytorch.org/whl/rocm6.4 && uv pip install --python '${QWEN_ASR_VENV}/bin/python' -r '${QWEN_ASR_DIR}/tools/qwen_asr_requirements.txt'"
    return 1
  fi
  cd "${QWEN_ASR_DIR}" || return 1
  PYTHONIOENCODING=utf-8 HF_HUB_DISABLE_SYMLINKS_WARNING=1 "${QWEN_ASR_VENV}/bin/python" tools/qwen_asr_server.py
}

left_pane_command() {
  local btop_bin=""
  if [ "${OLLAMA_TMUX_BTOP:-1}" != "0" ]; then
    if [ -x "${HOME}/.local/bin/btop" ]; then
      btop_bin="${HOME}/.local/bin/btop"
    elif command -v btop >/dev/null 2>&1; then
      btop_bin="$(command -v btop)"
    fi
  fi
  if [ -n "${btop_bin}" ]; then
    printf "'%s'; exec bash" "${btop_bin}"
  else
    printf "exec bash"
  fi
}

env_args() {
  local name
  ENV_ARGS=()
  for name in "$@"; do
    [ -n "${!name}" ] && ENV_ARGS+=(-e "${name}=${!name}")
  done
}

is_running() {
  tmux has-session -t "=${SESSION}" 2>/dev/null
}

start_session() {
  if is_running; then
    echo "${SESSION} is already running."
    return 0
  fi
  tmux new-session -d -s "${SESSION}" -c "${HOME}" "$(left_pane_command)"
  env_args "${OLLAMA_ENV[@]}"
  tmux split-window -d -h -t "=${SESSION}:" -c "${HOME}" "${ENV_ARGS[@]}" "bash '${SCRIPT}' serve"
  if [ "${QWEN_ASR_ENABLED}" != "0" ]; then
    env_args "${QWEN_ENV[@]}"
    tmux split-window -d -v -t "=${SESSION}:.1" -c "${HOME}" "${ENV_ARGS[@]}" "bash '${SCRIPT}' serve-asr"
  fi
  echo "${SESSION} started."
}

stop_session() {
  if tmux kill-session -t "=${SESSION}" 2>/dev/null; then
    echo "${SESSION} stopped."
  else
    echo "${SESSION} is not running."
  fi
}

port_of() {
  local port="${1##*:}"
  case "${port}" in
    '' | *[!0-9]*) echo "$2" ;;
    *) echo "${port}" ;;
  esac
}

PORTS=("$(port_of "${OLLAMA_HOST}" 11434)")
[ "${QWEN_ASR_ENABLED}" != "0" ] && PORTS+=("$(port_of "${QWEN_ASR_PORT}" 8178)")

ports_free() {
  local port
  for port in "${PORTS[@]}"; do
    ss -lnt 2>/dev/null | awk '{print $4}' | grep -q ":${port}\$" && return 1
  done
  return 0
}

wait_for_exit() {
  for _ in $(seq 1 30); do
    ports_free && return 0
    sleep 1
  done
  echo "ports ${PORTS[*]} are still in use after 30s. check: ss -lntp | grep -E ':(${PORTS[0]}|${PORTS[1]:-x})'"
  return 1
}

attach_if_terminal() {
  if [ -t 0 ] && [ -z "${TMUX}" ]; then
    exec tmux attach -t "=${SESSION}"
  fi
}

case "${1:-start}" in
  start)
    start_session
    attach_if_terminal
    ;;
  stop)
    stop_session
    ;;
  restart)
    stop_session
    wait_for_exit || exit 1
    start_session
    attach_if_terminal
    ;;
  view)
    if ! is_running; then
      echo "${SESSION} is not running. start it: $0 start"
      exit 1
    fi
    if [ -n "${TMUX}" ]; then
      echo "already inside tmux. detach first (Ctrl+b then d), then run: $0 view"
      exit 1
    fi
    exec tmux attach -r -t "=${SESSION}"
    ;;
  serve)
    serve
    echo "ollama exited. press enter to close this pane."
    read -r
    ;;
  serve-asr)
    serve_asr
    echo "qwen asr exited. press enter to close this pane."
    read -r
    ;;
  *)
    echo "usage: $0 [start|stop|restart|view]"
    exit 1
    ;;
esac
