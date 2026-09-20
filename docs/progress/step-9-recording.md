# Step 9: 녹음 + 실시간 자막 (WebSocket + Faster-Whisper)

- 기간: 2026-09-20
- 커밋: (검토 후 커밋 예정)
- 범위: SPEC §2.1-2(브라우저 ↔ Spring Boot), §2.2-5(Spring Boot ↔ FastAPI) 두 WebSocket 연결, 실시간 STT, WAV 저장

## 전체 흐름

```
브라우저 마이크
  └ AudioWorklet (Float32 → 16bit PCM)
      └ 3초마다 WebSocket 전송  ──▶ Spring Boot  /ws/v1/lectures/{id}/audio?token=...
                                     ├ {storage}/audio/{id}.pcm 에 누적
                                     └ 그대로 전달 ──▶ FastAPI /ai/v1/lectures/{id}/audio-stream
                                                        └ 3초 분량마다 Faster-Whisper(base) 전사
                                     ◀── TRANSCRIPT_PREVIEW ──┘
  ◀── 자막 중계 ──┘
연결 종료 시: PCM → WAV 변환, lectures.audio_url 기록, status READY
```

강의 상태는 녹음 시작 시 `RECORDING`, 종료 시 `READY`로 바뀝니다.

## 구현한 것

### FastAPI (`ai-engine`)
| 파일 | 내용 |
|---|---|
| `routers/audio.py` | `WS /ai/v1/lectures/{id}/audio-stream`. PCM을 3초 단위로 모아 전사하고 `TRANSCRIPT_PREVIEW` 전송 |
| `services/stt_service.py` | Faster-Whisper 모델 로딩(캐시), `pcm16_to_float32()`, `transcribe_pcm()` |
| `core/config.py` | `whisper_realtime_model_name`(base), `whisper_device`, `whisper_compute_type`, `whisper_language`(ko), `audio_sample_rate`, `realtime_chunk_seconds` |

- Whisper 추론은 CPU를 오래 붙잡기 때문에 `run_in_threadpool`로 넘겨 이벤트 루프를 막지 않습니다.
- VAD 필터를 켜서 무음 구간에서는 빈 문자열이 나오고, 이때는 이벤트를 보내지 않습니다.

### Spring Boot (`server-core`)
| 파일 | 내용 |
|---|---|
| `websocket/AudioStreamWebSocketHandler.java` | 토큰 검증 → 소유자 확인 → FastAPI 연결 → PCM 저장/전달 → 자막 중계 → 종료 처리 |
| `config/WebSocketConfig.java` | 핸들러 등록, 허용 Origin, 바이너리 버퍼 512KB |
| `config/AudioProperties.java` | `lecturemate.audio.sample-rate` (16000) |
| `service/StorageService.java` | `openPcmSink()`, `finalizeWav()` (44바이트 WAV 헤더 직접 생성) |
| `service/LectureService.java` | `attachAudio()` — audio_url 기록 + 상태 READY |
| `config/SecurityConfig.java` | `/ws/**`는 permitAll (핸들러가 토큰을 직접 검증) |

### web-client
| 파일 | 내용 |
|---|---|
| `public/pcm-worklet.js` | AudioWorklet: Float32 → Int16 변환 후 메인 스레드로 전달 |
| `components/AudioRecorder.tsx` | 마이크 권한, 16kHz AudioContext, 3초 청크 전송, 자막 표시, 종료 시 잔여분 flush |
| `app/lectures/[id]/page.tsx` | 녹음 중 상태 폴링 |

## 이렇게 한 이유

- **PCM 16kHz 모노 (사용자 결정):** 청크마다 독립적으로 디코딩할 수 있어 실시간 STT에 바로 넣을 수 있습니다. WebM/Opus는 첫 청크에만 헤더가 있어 우회 처리가 필요하고, SPEC이 요구하는 `.wav` 저장에도 변환이 한 번 더 듭니다. 대신 용량이 큽니다(시간당 약 115MB).
- **쿼리 파라미터 토큰 (사용자 결정):** 브라우저는 WebSocket 요청에 `Authorization` 헤더를 붙일 수 없습니다. 서버 접근 로그에 토큰이 남을 수 있다는 점은 감수하고, 운영 전에 단기 티켓 방식으로 바꿀 수 있게 검증 지점을 한 곳(핸들러)에 모았습니다.
- **실시간은 base 모델:** large-v3는 CPU에서 3초 청크를 따라가지 못합니다. SPEC §4.2도 "Large-v3 및 Base 모델"을 함께 쓴다고 정의합니다. 배치 정밀 전사는 다음 단계에서 large-v3를 씁니다.
- **언어 고정(`ko`):** 청크마다 언어를 자동 감지하면 느리고 결과가 흔들립니다. `WHISPER_LANGUAGE`로 바꿀 수 있습니다.
- **WAV를 직접 만드는 이유:** 44바이트 헤더만 붙이면 되는 단순한 포맷이라 ffmpeg 같은 외부 의존성을 들이지 않았습니다. 녹음 중에는 `.pcm`에 이어 붙이고, 끝날 때 헤더를 붙여 `.wav`로 만듭니다.
- **`/ws/**`를 Security에서 permitAll:** 핸드셰이크 단계에서 Spring Security가 막으면 토큰 검증 로직을 태울 수 없습니다. 대신 핸들러가 직접 토큰과 소유자를 확인하고, 실패 시 1008로 끊습니다.
- **녹음 종료는 WebSocket 종료로 처리:** SPEC §2.1-3(`/recording/finish` → `ANALYZING`)은 배치 분석이 있어야 의미가 있어 다음 단계로 미뤘습니다. 지금은 종료 시 `READY`로 돌아갑니다.

## 트러블슈팅

| 문제 | 원인 | 해결 |
|---|---|---|
| 기존 테스트 전부 실패 (`Attribute 'jakarta.websocket.server.ServerContainer' not found`) | 버퍼 크기 설정용 `ServletServerContainerFactoryBean`은 실제 서블릿 컨테이너가 있어야 만들어짐. MOCK 환경 테스트에서는 없음 | 해당 빈을 `@Profile("!test")`로 제외 |
| Boot 4에서 `LocalServerPort` import 실패 | 패키지가 `org.springframework.boot.test.web.server` | import 수정 |
| 화면에 `RECORDING` 상태가 안 보임 | WebSocket `open` 이벤트가 서버의 상태 변경보다 먼저 발생해, 한 번만 조회하면 이전 상태를 읽음 | 녹음 중에는 2초 간격으로 상태를 다시 조회 |
| 녹음 종료 후 `READY`로 안 바뀜 | 종료 직후 한 번만 조회해 서버 처리 전 값을 읽고, 폴링은 이미 멈춤 | 폴링 조건을 "클라이언트가 녹음 중 **또는** 서버 상태가 PROCESSING/RECORDING"으로 변경 |
| 마지막 3초 미만 구간이 저장되지 않음 | 3초를 채워야 전송하는데 종료 시 잔여분을 버림 | 종료 시 `flush()`로 남은 PCM을 마저 전송 |
| **헤드리스 Chrome의 가짜 마이크가 무음** | `--use-file-for-fake-audio-capture`가 이 환경에서 동작하지 않음 (16kHz/48kHz 모두 peak ≈ 0.0002) | 테스트에서 `getUserMedia`를 음성 파일을 재생하는 `MediaStreamDestination`으로 대체. AudioWorklet부터 서버까지는 실제 코드 경로 그대로 검증 |

## 검증 결과

**자동 테스트 (총 34개 통과)**
- Spring Boot 19개: 기존 15개 + `AudioStreamWebSocketTest` 4개(토큰 없음/잘못된 토큰/타인 토큰/없는 강의 → 모두 연결 거부)
- FastAPI 10개: 기존 5개 + `test_audio_stream.py` 3개(3초 임계값, 타임스탬프 누적, 무음 시 미전송), `test_stt_service.py` 2개(PCM 변환)
- web-client 5개, lint·타입 검사·빌드 통과

**직접 확인한 실시간 STT** (FastAPI 단독, 한국어 TTS 음성 주입)
```
[11.7s] {"type":"TRANSCRIPT_PREVIEW","startTimeMs":0,"endTimeMs":3000,
        "text":"오늘은 다익스트라 최단 경로 알고리즘을 공부합니다."}
[12.1s] {"startTimeMs":3000,"endTimeMs":6000,"text":"음소 가중치가 있으면 벨만 포드를"}
```
base 모델이라 "음수"를 "음소"로 잘못 듣습니다. 정밀 전사는 배치 단계에서 large-v3로 다시 합니다.

**브라우저 전 구간 검증** (헤드리스 Chrome + 서버 3개)

| 단계 | 결과 |
|---|---|
| 회원가입 → PDF 업로드 | lectureId 발급, `READY` |
| 녹음 시작 | 화면 상태 `RECORDING` |
| 실시간 자막 | "오늘은 다익스트라 최단 경로 알고리즘을 공부하..." 화면 표시 |
| 녹음 종료 | 상태 `READY` |
| 저장 결과 | `audio/7.wav` 3.49초(잔여분 flush 포함), `lectures.audio_url = /files/audio/7.wav` |
| 저장본 재전사 | "오늘은 다익스트라 최단 경로 알고리즘을 공부합니다." — 정상 저장 확인 |

검증에 쓴 계정, 강의, 오디오/PDF 파일은 모두 삭제했습니다.

## 남은 일

- `POST /api/v1/lectures/{id}/recording/finish`(§2.1-3)와 배치 정밀 분석(§2.2-2) — 다음 단계
- 첫 실행 시 Whisper base 모델(약 74MB) 자동 다운로드. EC2 배포 시 미리 받아 두는 편이 좋습니다
- 긴 녹음 중 네트워크가 끊기면 재연결 로직이 없습니다
- 시간당 약 115MB PCM이 쌓입니다. 장시간 녹음 대비 압축이나 정리 정책이 필요합니다

## 사용법

```bash
docker compose up -d postgres
cd ai-engine && source .venv/bin/activate && uvicorn main:app --port 8000   # 첫 실행 시 모델 다운로드
cd server-core && ./gradlew bootRun
cd web-client && pnpm dev
# /lectures/{id} 에서 "녹음 시작" → 마이크 권한 허용 → 자막 확인 → "녹음 종료"
```
