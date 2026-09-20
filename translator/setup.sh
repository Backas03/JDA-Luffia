#!/bin/bash
set -e
cd "$(dirname "$0")"
python3 -m venv .venv
. .venv/bin/activate
pip install --upgrade pip
pip install -r requirements.txt
pip install torch --index-url https://download.pytorch.org/whl/cpu
ct2-transformers-converter --model facebook/nllb-200-3.3B --output_dir models/nllb-200-3.3B-int8 --quantization int8 --force
echo "setup complete. run: ./run.sh"
