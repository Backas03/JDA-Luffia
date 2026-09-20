#!/bin/bash
cd "$(dirname "$0")"
. .venv/bin/activate
exec uvicorn server:app --host 127.0.0.1 --port "${TRANSLATOR_PORT:-8765}" --workers 1
