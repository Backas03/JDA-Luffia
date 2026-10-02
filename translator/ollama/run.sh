#!/bin/bash
export PATH="/usr/local/bin:${PATH}"
export OLLAMA_HOST="${OLLAMA_HOST:-0.0.0.0:11434}"
export OLLAMA_CONTEXT_LENGTH="${OLLAMA_CONTEXT_LENGTH:-8192}"
export OLLAMA_KEEP_ALIVE="${OLLAMA_KEEP_ALIVE:--1}"
export OLLAMA_NUM_PARALLEL="${OLLAMA_NUM_PARALLEL:-1}"
export OLLAMA_MAX_LOADED_MODELS="${OLLAMA_MAX_LOADED_MODELS:-1}"
SESSION="${OLLAMA_TMUX_SESSION:-ollama}"
SCRIPT="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"

serve() {
  if systemctl is-active --quiet ollama 2>/dev/null; then
    echo "ollama systemd service is running. stop it first: sudo systemctl disable --now ollama"
    return 1
  fi
  ollama serve
}

case "${1:-start}" in
  start)
    if tmux has-session -t "=${SESSION}" 2>/dev/null; then
      echo "${SESSION} is already running."
    else
      ENV_ARGS=()
      for name in OLLAMA_HOST OLLAMA_CONTEXT_LENGTH OLLAMA_KEEP_ALIVE OLLAMA_NUM_PARALLEL OLLAMA_MAX_LOADED_MODELS; do
        ENV_ARGS+=(-e "${name}=${!name}")
      done
      tmux new-session -d -s "${SESSION}" -c "${HOME}"
      tmux split-window -d -h -t "=${SESSION}:" -c "${HOME}" "${ENV_ARGS[@]}" "bash '${SCRIPT}' serve"
      echo "${SESSION} started."
    fi
    if [ -t 0 ] && [ -z "${TMUX}" ]; then
      exec tmux attach -t "=${SESSION}"
    fi
    ;;
  stop)
    if tmux kill-session -t "=${SESSION}" 2>/dev/null; then
      echo "${SESSION} stopped."
    else
      echo "${SESSION} is not running."
    fi
    ;;
  serve)
    serve
    echo "ollama exited. press enter to close this pane."
    read -r
    ;;
  *)
    echo "usage: $0 [start|stop]"
    exit 1
    ;;
esac
