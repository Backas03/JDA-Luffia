# JDA-Luffia
디스코드 음악 봇입니다. 여러 음성채팅방 동시 재생, 실시간 가사와 한국어 번역, AI 대기열 관리, 롤 전적 검색을 지원합니다.

제작: Discord bagkaseu(박카스#9970) · 오류나 건의는 [Issue](https://github.com/Backas03/JDA-Luffia/issues) 로 남겨 주세요.

## 명령어
| 명령어 | 설명 |
|---|---|
| ``/재생 <검색어·링크> (출처)`` | 유튜브·스포티파이 곡/앨범/플레이리스트 재생 |
| ``/대기열`` | 현재 곡, 대기열, 다음 추천 보기 |
| ``/스킵 (곡수)`` | 건너뛰기 |
| ``/일시정지`` | 일시정지 / 다시 재생 |
| ``/셔플`` | 대기열 무작위 섞기 |
| ``/ai셔플`` | 비슷한 곡끼리 이어지게 섞기 |
| ``/반복모드 <모드>`` | 현재 곡 / 전체 / 반복 없음 |
| ``/볼륨 (0~200)`` | 볼륨 변경 (기본 10) |
| ``/속도 <0.2~3.0>`` | 재생 속도 변경 |
| ``/이퀄라이저 <모드>`` | 저음 강조, 보컬 강조 등 |
| ``/노래방모드 (모드)`` | 에코 / 보컬 제거 / 끄기 |
| ``/가사 (모드) (오프셋)`` | 실시간·전체 가사 표시, 싱크 조정(ms) |
| ``/ai <요청>`` | 자연어로 봇 조작 |
| ``/ai관리 <켜기·끄기·상태·디버그>`` | AI 기능 관리 (제작자 전용) |
| ``/나가기`` | 대기열 초기화 후 퇴장 |
| ``/롤정보 <닉네임#태그>`` | 롤 전적 검색 |
| ``/도움말`` | 도움말 |

**가사**: 곡이 시작되면 곡 카드에 실시간 가사(LRCLIB)를 띄우고, 번역 서버가 있으면 한국어 번역을 줄마다 붙입니다. 유튜브 제목으로 가사를 못 찾으면 AI 로 곡명을 추정해 다시 찾습니다.

**/ai**: 요청을 읽고 필요한 기능을 차례로 실행합니다.
- 예) ``요즘 유행하는 jpop 틀어줘`` · ``일본 노래만 남겨줘`` · ``3번이랑 5번 곡 빼줘`` · ``다음 곡으로 YOASOBI 아이돌 틀어줘`` · ``이 노래 누가 만들었어?``
- 곡 삭제는 "빼줘·지워줘·남겨줘" 같은 말이 있을 때만 하고, 5곡이 넘으면 버튼으로 확인합니다.
- 대기열이 비면 비슷한 곡을 미리 골라 이어서 틀어 줍니다 ("추천 그만" 으로 끔).
- 사용자별 20초에 1번, 서버별 시간당 60번까지 쓸 수 있습니다.

## 설치
### 1. config.yaml 작성
실행 폴더의 ``config.yaml`` 에 설정을 적습니다. 없으면 첫 실행 때 기본값으로 만들어 주고, 적지 않은 키는 [기본값](src/main/resources/config.yaml)을 씁니다. 다른 경로는 ``-Dluffia.config=<경로>`` 로 지정합니다. 토큰이 들어가니 커밋하지 마세요.

```yaml
discord:
  token: "<봇 토큰>"
  music-bot-tokens: []
  service-guilds: []
riot:
  api-key: ""
sources:
  spotify:
    client-id: ""
    client-secret: ""
    refresh-token: ""
llm:
  endpoints:
    - url: http://127.0.0.1:8765
whisper:
  endpoints: []
```

| 키 | 설명 |
|---|---|
| ``discord.token`` | 봇 토큰 (필수) |
| ``bot.dev``, ``discord.dev-token`` | ``bot.dev: true`` 면 개발용 토큰으로 로그인 |
| ``discord.music-bot-tokens`` | 추가 노래봇 토큰 |
| ``discord.service-guilds`` | 명령어를 받을 서버 id. 비우면 모든 서버 |
| ``riot.api-key`` | 롤 전적 검색 |
| ``sources.spotify.*`` | 스포티파이 지원. 비우면 스포티파이만 꺼짐 (앱 소유자 Premium 필요) |
| ``llm.*`` | 가사 번역·AI 서버. 아래 "가사 번역" |
| ``whisper.endpoints`` | 가사 싱크 보정·AI 가사 변환. 아래 "Whisper" |

모든 키는 ``LUFFIA_<경로>`` 환경 변수로 덮어쓸 수 있습니다 (예: ``LUFFIA_DISCORD_TOKEN``, 목록은 쉼표 구분). ``llm.endpoints``, ``whisper.endpoints`` 는 파일에만 적을 수 있습니다.

### 2. 실행
- JDK 25 이상이 필요합니다 (디스코드 음성 암호화 라이브러리 JDAVE 요구사항).
- 개발: ``./gradlew run``
- 배포: ``./gradlew fatJar`` 로 ``build/libs/JDA-Luffia-1.0.0-SNAPSHOT-all.jar`` 를 만들고, ``config.yaml`` 이 있는 폴더에서 ``java -Dfile.encoding=UTF-8 -jar JDA-Luffia-1.0.0-SNAPSHOT-all.jar``

## 설정 가이드
### 여러 음성채팅방 동시 재생
봇 하나가 서버마다 음성채팅방 하나를 맡습니다. 더 필요하면 봇 계정을 만들어 ``discord.music-bot-tokens`` 에 넣고 서버에 초대합니다.

![image](https://github.com/Backas03/JDA-Luffia/assets/71801733/8850d664-b12c-4569-b403-59e358bb796c)
![image](https://github.com/Backas03/JDA-Luffia/assets/71801733/95db993f-c22c-4c16-86cd-f8f3a28da4b5)

### 스포티파이 플레이리스트
1. Spotify 개발자 대시보드의 앱 Redirect URI 에 ``http://127.0.0.1:8888/callback`` 추가
2. ``sources.spotify.client-id`` / ``client-secret`` 을 채우고 ``./gradlew spotifyLogin`` 실행 후 로그인
3. 출력된 refresh token 을 ``sources.spotify.refresh-token`` 에 넣고 재시작

로그인한 계정이 만들었거나 저장한 플레이리스트만 재생됩니다. 아티스트 링크는 지원하지 않습니다.

### 가사 번역
아래 중 하나를 띄우고 주소를 ``llm.endpoints`` 에 넣은 뒤 재시작합니다. 같은 서버를 ``/ai`` 도 씁니다.

| 방식 | 실행 | 주소 |
|---|---|---|
| llama.cpp + Tri-7B (CPU, 권장) | ``translator/llm/setup.sh`` → ``translator/llm/run.sh`` | ``http://127.0.0.1:8765`` |
| NLLB (가볍지만 품질 낮음) | ``translator/setup.sh`` → ``translator/run.sh`` | ``http://127.0.0.1:8765`` |
| Ollama (GPU, 가장 빠름) | ``ollama pull gemma4:12b`` | ``http://127.0.0.1:11434`` |

- llama.cpp 는 RAM 약 6GB 를 씁니다. 포트는 ``TRANSLATOR_PORT``, 스레드는 ``LLM_THREADS`` 로 바꿉니다. Windows GPU PC 는 llama.cpp CUDA 릴리스를 ``translator/llm/bin`` 에 풀고 ``run-gpu.bat`` 로 실행합니다.
- Ollama 는 ``OLLAMA_CONTEXT_LENGTH=8192``, ``OLLAMA_KEEP_ALIVE=-1`` 을 권장합니다. 다른 PC 에서 접속하면 ``OLLAMA_HOST=0.0.0.0`` 도 설정합니다. Linux 에서는 ``bash translator/ollama/run.sh`` 로 tmux 에 띄울 수 있습니다 (``start|stop|restart|view``). 같은 세션에 Qwen3-ASR 서버도 함께 올립니다. ``QWEN_ASR_DIR`` (기본 ``~/qwen-asr``) 에 저장소의 ``tools/`` 만 sparse clone 해 두고 그 안의 ``.venv-qwen`` 을 쓰며, 없으면 해당 pane 에 받는 명령을 찍어 줍니다. ``QWEN_ASR_ENABLED=0`` 으로 끕니다.
- 모델은 ``llm.model-override`` 로 고정합니다 (서버별 ``model`` 이 우선).

### 여러 GPU 서버
```yaml
llm:
  endpoints:
    - url: http://gpu-pc-1:11434
      label: RTX 5080
      model: luffia
      slots: 4
    - url: http://gpu-pc-2:11434
      label: RX 7800 XT
  fallback:
    url: http://127.0.0.1:8765
    label: CPU
    start-command: /home/<사용자>/JDA-Luffia/translator/llm/run.sh
    stop-command: pkill -f llama-server
```
- ``slots``: 서버에 동시에 넣을 요청 수 (1~8, 기본 1). Ollama 의 ``OLLAMA_NUM_PARALLEL`` 과 맞춥니다.
- 요청은 측정한 속도를 보고 가장 빨리 끝날 GPU 로 보냅니다. 현재 곡 번역과 ``/ai`` 가 미리 번역보다 우선합니다.
- ``fallback``: GPU 가 모두 꺼졌을 때만 쓰는 CPU 서버입니다. 비우면 ``endpoints`` 가 2개 이상일 때 마지막 항목이 대신합니다. ``start-command`` / ``stop-command`` 를 적으면 봇이 GPU 상태에 따라 CPU 서버를 켜고 끕니다 (Linux 전용).
- 서버 상태와 속도는 ``/ai관리 상태`` 로 확인합니다.
- ``luffia`` 모델은 아래 ``Modelfile`` 로 ``ollama create luffia -f Modelfile`` 을 실행해 만듭니다.
```
FROM gemma4:12b
PARAMETER num_ctx 8192
```

### Whisper
``whisper.endpoints`` 에 서버(``url``, ``label``)를 적으면 가사 싱크 자동 보정과 AI 가사 변환(타임스탬프 없는 가사에 시각 붙이기)이 켜집니다. 서버는 OpenAI 호환 ``/v1/audio/transcriptions`` 로 단어별 타임스탬프(``words``)를 돌려줘야 합니다. whisper.cpp 서버는 ``url`` 에 ``/inference`` 까지 적습니다 (예: ``http://gpu-pc-2:8178/inference``).

### Qwen3-ASR 서버
``tools/qwen_asr_server.py`` 는 Qwen3-ASR + Qwen3-ForcedAligner 로 같은 ``/v1/audio/transcriptions`` 응답을 만드는 서버라 ``whisper.endpoints`` 에 그대로 적으면 됩니다.
```
pip install torch --index-url https://download.pytorch.org/whl/cu128
pip install -r tools/qwen_asr_requirements.txt
QWEN_ASR_PORT=8001 python tools/qwen_asr_server.py
```
보컬 분리를 쓰려면 ``pip install "audio-separator[gpu]" audioread`` 를 추가로 설치하고, 이때 torch 가 CPU 빌드로 바뀌므로 첫 줄의 torch 설치를 다시 실행합니다 (torchvision 이 딸려 오면 ``pip uninstall torchvision``).
| 변수 | 기본값 | 설명 |
|---|---|---|
| ``QWEN_ASR_MODEL`` | ``Qwen/Qwen3-ASR-1.7B`` | ASR 모델 |
| ``QWEN_ASR_ALIGNER`` | ``Qwen/Qwen3-ForcedAligner-0.6B`` | 단어 타임스탬프용 정렬 모델 |
| ``QWEN_ASR_ALIGN`` | ``1`` | ``0`` 이면 정렬을 끄고 세그먼트 텍스트만 돌려줌 (평가용) |
| ``QWEN_ASR_BACKEND`` | ``transformers`` | ``vllm`` 은 Linux 에서만 |
| ``QWEN_ASR_DEVICE`` / ``QWEN_ASR_ATTN`` | ``cuda`` / ``sdpa`` | Windows 에는 flash-attn 이 없으므로 ``sdpa`` 유지 |
| ``QWEN_ASR_SEPARATE`` | ``off`` | ``off``: 원음 그대로. ``input``: 보컬만 분리해 인식. ``vad``: 보컬 에너지로 노래 구간만 찾아 원음을 그 구간만 인식 (환각 억제) |
| ``QWEN_ASR_SEPARATOR_MODEL`` | ``htdemucs.yaml`` | 분리 모델. ``model_bs_roformer_ep_317_sdr_12.9755.ckpt`` 가 조금 더 정확하지만 2배 느림 |
| ``QWEN_ASR_SEPARATOR_OVERLAP`` / ``QWEN_ASR_SEPARATOR_BATCH`` | ``2`` / ``4`` | BS-RoFormer 겹침 횟수와 배치. 라이브러리 기본값 8/1 은 곡당 100초가 넘게 걸림 |
| ``QWEN_ASR_CHUNK_SECONDS`` | ``30`` | 조용한 지점에서 자르는 길이. ``0`` 이면 자르지 않음 |
| ``QWEN_ASR_VAD_THRESHOLD`` | ``0.1`` | ``vad`` 모드에서 노래로 볼 에너지 비율 |
| ``QWEN_ASR_HOST`` / ``QWEN_ASR_PORT`` | ``0.0.0.0`` / ``8000`` | 바인드 주소 |

``prompt`` 폼 필드를 보내면 인식 문맥으로 전달합니다. ``language`` 가 없으면 청크별 감지 결과의 다수 언어로 고정해 어긋난 청크를 다시 인식하지만, 힌트를 주는 쪽이 훨씬 정확합니다. 정렬 모델은 한국어·일본어·영어·중국어 등 11개 언어만 지원하므로 그 밖의 언어는 ``QWEN_ASR_ALIGN=0`` 으로 씁니다.

``tools/asr_eval.py`` 는 폴더의 곡(``.mp3`` 등)과 같은 이름의 ``.txt``/``.lrc`` 가사를 비교해 엔드포인트별 CER/WER 를 냅니다 (ffmpeg 필요, WER 은 일본어에 의미 없음). 곡 이름과 같은 ``.lang`` 파일에 ``ko`` 처럼 적으면 언어 힌트로 보냅니다.
```
python tools/asr_eval.py --dir songs --endpoint whisper=http://gpu-pc-1:8000 --endpoint qwen=http://gpu-pc-1:8001 --out results.json
```
RTX 5080, 언어 힌트 포함, 영어·한국어·일본어 1곡씩 잰 CER 과 곡당 소요 시간입니다 (표본이 작으니 참고만).

| 설정 | 영어 | 한국어 | 일본어 | 곡당 |
|---|---|---|---|---|
| ``SEPARATE=off`` | 3.4% | 12.5% | 8.7% | 3~6초 |
| ``SEPARATE=vad`` + htdemucs | 1.7% | 11.7% | 9.4% | 17~20초 |
| ``SEPARATE=vad`` + BS-RoFormer | 1.3% | 12.1% | 7.5% | 약 40초 |
| ``SEPARATE=input`` + htdemucs | 2.7% | 13.3% | 12.0% | 14~16초 |

힌트 없이 일본어 곡을 넣으면 ``off`` 기준 12.4% 로 떨어지므로 Java 가 보내는 언어 힌트가 중요합니다.

### 로그
``logs/luffia.log`` (매일·크기별 분할, 14일 보관) 와 ``logs/luffia-error.log`` (WARN 이상) 에 기록합니다. ``config.yaml`` 의 ``logging.dir``, ``logging.level``, ``logging.console`` 로 위치·레벨·콘솔 출력을 바꿉니다.

### 캐시
번역, 가사, AI 태그, 서버 속도 등을 ``cache/`` 에 저장해 재시작 후에도 씁니다. 위치는 ``cache.dir`` 로 바꾸고, 폴더를 지우면 초기화됩니다.

## 데모
6코어 CPU 로 LLM 번역을 돌린 영상입니다. GPU 를 쓰면 더 빠릅니다.

https://github.com/user-attachments/assets/7bd6bd92-021b-4578-8bd5-e2e310b9ec6b

https://github.com/user-attachments/assets/5cb94339-9e2c-490b-86a9-42d63d0af4d4

## 게임 전적 검색
- ``/롤정보 <닉네임#태그>``
- ``!메이플정보 <닉네임>`` (불안정)

![image](https://github.com/Backas03/JDA-Luffia/assets/71801733/6d5395d0-db25-4b94-bb34-c98561824c17)

## 개발
- 명령어 끄기: ``Luffia.java`` 에서 해당 ``registerSlashCommand`` / ``registerCommand`` 줄을 주석 처리합니다.
- prefix 변경: ``Luffia.java`` 의 ``new CommandManager("!", this)`` 에서 ``"!"`` 를 바꿉니다 (슬래시 명령어는 영향 없음).
- PR 코드는 시작과 끝을 ``/* [닉네임] add ... start */``, ``/* [닉네임] add ... end */`` 로 표기합니다.
