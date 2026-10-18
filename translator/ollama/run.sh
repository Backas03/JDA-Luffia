#!/bin/bash
[ -f "${HOME}/.config/ai-server.env" ] && . "${HOME}/.config/ai-server.env"
export PATH="/usr/local/bin:${PATH}"
export OLLAMA_HOST="${OLLAMA_HOST:-0.0.0.0:11434}"
export OLLAMA_CONTEXT_LENGTH="${OLLAMA_CONTEXT_LENGTH:-8192}"
export OLLAMA_KEEP_ALIVE="${OLLAMA_KEEP_ALIVE:--1}"
export OLLAMA_NUM_PARALLEL="${OLLAMA_NUM_PARALLEL:-1}"
export OLLAMA_MAX_LOADED_MODELS="${OLLAMA_MAX_LOADED_MODELS:-1}"
export WHISPER_ENABLED="${WHISPER_ENABLED:-1}"
export WHISPER_CPP_DIR="${WHISPER_CPP_DIR:-${HOME}/whisper.cpp}"
export WHISPER_BIN="${WHISPER_BIN:-${WHISPER_CPP_DIR}/build/bin/whisper-server}"
export WHISPER_MODEL="${WHISPER_MODEL:-${WHISPER_CPP_DIR}/models/ggml-large-v3-turbo-q8_0.bin}"
export WHISPER_HOST="${WHISPER_HOST:-0.0.0.0}"
export WHISPER_PORT="${WHISPER_PORT:-8178}"
export WHISPER_INFERENCE_PATH="${WHISPER_INFERENCE_PATH:-/v1/audio/transcriptions}"
export WHISPER_ARGS="${WHISPER_ARGS--fa}"
SESSION="${AI_SERVER_TMUX_SESSION:-ai-server}"
SCRIPT="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
OLLAMA_ENV=(OLLAMA_HOST OLLAMA_CONTEXT_LENGTH OLLAMA_KEEP_ALIVE OLLAMA_NUM_PARALLEL OLLAMA_MAX_LOADED_MODELS)
WHISPER_ENV=(WHISPER_CPP_DIR WHISPER_BIN WHISPER_MODEL WHISPER_HOST WHISPER_PORT WHISPER_INFERENCE_PATH WHISPER_ARGS)

serve() {
  if systemctl is-active --quiet ollama 2>/dev/null; then
    echo "ollama systemd service is running. stop it first: sudo systemctl disable --now ollama"
    return 1
  fi
  ollama serve
}

serve_whisper() {
  if ! command -v "${WHISPER_BIN}" >/dev/null 2>&1; then
    echo "${WHISPER_BIN} not found. build whisper.cpp with Vulkan (needs libvulkan-dev and glslc):"
    echo "  git clone https://github.com/ggml-org/whisper.cpp '${WHISPER_CPP_DIR}' && cd '${WHISPER_CPP_DIR}' && cmake -B build -DGGML_VULKAN=1 -DCMAKE_BUILD_TYPE=Release && cmake --build build --config Release -j 16"
    return 1
  fi
  if [ ! -f "${WHISPER_MODEL}" ]; then
    echo "${WHISPER_MODEL} not found. download it:"
    echo "  sh '${WHISPER_CPP_DIR}/models/download-ggml-model.sh' large-v3-turbo-q8_0"
    return 1
  fi
  "${WHISPER_BIN}" -m "${WHISPER_MODEL}" --host "${WHISPER_HOST}" --port "${WHISPER_PORT}" \
    --inference-path "${WHISPER_INFERENCE_PATH}" ${WHISPER_ARGS}
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
  if [ "${WHISPER_ENABLED}" != "0" ]; then
    env_args "${WHISPER_ENV[@]}"
    tmux split-window -d -v -t "=${SESSION}:.1" -c "${HOME}" "${ENV_ARGS[@]}" "bash '${SCRIPT}' serve-whisper"
  fi
  echo "${SESSION} started."
}

stop_session() {
  if tmux kill-session -t "=${SESSION}" 2>/dev/null; then
    echo "${SESSION} stopped."
  else
    echo "${SESSION} is not running."
  fi
  kill_leftovers
}

kill_leftovers() {
  local port
  pkill -f 'ollama runner' 2>/dev/null
  for port in "${PORTS[@]}"; do
    fuser -k -TERM "${port}/tcp" >/dev/null 2>&1
  done
}

port_of() {
  local port="${1##*:}"
  case "${port}" in
    '' | *[!0-9]*) echo "$2" ;;
    *) echo "${port}" ;;
  esac
}

PORTS=("$(port_of "${OLLAMA_HOST}" 11434)")
[ "${WHISPER_ENABLED}" != "0" ] && PORTS+=("$(port_of "${WHISPER_PORT}" 8178)")

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
  echo "ports ${PORTS[*]} are still in use after 30s. check: ss -lntp | grep -E ':($(IFS='|'; echo "${PORTS[*]}"))'"
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
  serve-whisper)
    serve_whisper
    echo "whisper.cpp exited. press enter to close this pane."
    read -r
    ;;
  *)
    echo "usage: $0 [start|stop|restart|view]"
    exit 1
    ;;
esac
