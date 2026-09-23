@echo off
cd /d "%~dp0"
if "%MODEL_FILE%"=="" set MODEL_FILE=trillionlabs.Tri-7B.Q4_K_M.gguf
if "%MODEL_ALIAS%"=="" set MODEL_ALIAS=Tri-7B
if "%TRANSLATOR_PORT%"=="" set TRANSLATOR_PORT=8765
if "%LLM_CACHE_RAM%"=="" set LLM_CACHE_RAM=0
bin\llama-server.exe -m "models\%MODEL_FILE%" -a "%MODEL_ALIAS%" --host 0.0.0.0 --port %TRANSLATOR_PORT% -ngl 99 -c 8192 -np 1 --cache-ram %LLM_CACHE_RAM% --no-webui
