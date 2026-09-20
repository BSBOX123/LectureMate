# 배포 가이드 (GPU EC2 1대 구성)

모든 구성요소를 GPU EC2 한 대에 `docker compose`로 올립니다. `ai-engine`과 `server-core`가 같은 디스크(볼륨)를 공유하므로 파일 경로 전달 방식이 그대로 동작하고, S3가 필요 없습니다.

```
                    ┌─────────────── EC2 (GPU) ───────────────┐
브라우저 ──HTTPS──▶ │ nginx ─┬─ web-client (Next.js)          │
                    │        ├─ server-core (Spring Boot) ──┐ │
                    │        └─ /ws → server-core           │ │
                    │                                        │ │
                    │ ai-engine (FastAPI, GPU) ◀─────────────┘ │
                    │   ├─ ollama (GPU)                        │
                    │   └─ postgres + pgvector                 │
                    │ 공유 볼륨: storage(PDF/오디오), model-cache│
                    └──────────────────────────────────────────┘
```

## 1. 인스턴스 준비

| 항목 | 권장 |
|---|---|
| 인스턴스 | `g5.xlarge` 또는 `g6.xlarge` (GPU 24GB) |
| 디스크 | 200GB 이상 (모델 캐시 약 20GB + 강의 파일) |
| OS | Ubuntu 22.04 LTS |

GPU 메모리 사용 추정: Qwen2.5-14B 4bit 약 9~10GB + Whisper large-v3 약 3GB + bge-m3 약 2GB.

```bash
# Docker + NVIDIA 컨테이너 툴킷
curl -fsSL https://get.docker.com | sh
sudo apt-get install -y nvidia-container-toolkit
sudo nvidia-ctk runtime configure --runtime=docker && sudo systemctl restart docker
nvidia-smi   # GPU 인식 확인
```

## 2. 환경 변수

```bash
git clone <저장소> && cd lecture-mate
cp deploy/env.prod.example .env.prod
openssl rand -base64 48   # JWT_SECRET, INTERNAL_API_SECRET 각각 생성
vi .env.prod              # PUBLIC_ORIGIN, DB 비밀번호, 시크릿 입력
```

`.env.prod`는 `.gitignore`에 있고 커밋 훅도 막습니다. **절대 커밋하지 마세요.**

| 변수 | 설명 |
|---|---|
| `PUBLIC_ORIGIN` | `https://도메인`. 프론트 빌드, CORS, 쿠키에 함께 쓰입니다 |
| `JWT_SECRET` | 32바이트 이상. 바꾸면 기존 로그인이 모두 무효화됩니다 |
| `INTERNAL_API_SECRET` | FastAPI → Spring Boot Webhook 인증 |
| `POSTGRES_PASSWORD` | DB 비밀번호 |

## 3. HTTPS 인증서

Refresh Token 쿠키가 `Secure`로 내려가므로 **HTTPS가 필수**입니다.

```bash
sudo certbot certonly --standalone -d 도메인
mkdir -p deploy/nginx/certs
sudo cp /etc/letsencrypt/live/도메인/{fullchain.pem,privkey.pem} deploy/nginx/certs/
```

인증서를 발급하기 전에 먼저 확인하고 싶다면 `deploy/nginx/lecturemate.conf`의 80번 포트 리다이렉트를 주석 처리하고 `location /`를 `web_client`로 보내면 됩니다.

## 4. 기동

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build

# LLM 모델 내려받기 (최초 1회, 약 9GB)
docker compose -f docker-compose.prod.yml exec ollama ollama pull qwen2.5:14b-instruct
```

Whisper와 bge-m3 모델은 첫 사용 시 자동으로 받아 `model-cache` 볼륨에 저장됩니다. 첫 PDF 업로드와 첫 분석이 느린 것은 정상입니다.

DB 스키마는 `server-core`가 기동할 때 Flyway가 적용합니다. 별도 작업이 필요 없습니다.

## 5. 확인

```bash
docker compose -f docker-compose.prod.yml ps          # 전부 Up 인지
docker compose -f docker-compose.prod.yml logs -f server-core | grep Started
curl -I https://도메인                                  # 200
curl -s -o /dev/null -w "%{http_code}\n" https://도메인/api/v1/users/me   # 401 이면 정상
```

그다음 브라우저에서 회원가입 → PDF 업로드 → 녹음 → 정밀 분석 → 질문까지 한 번 돌려 봅니다.

## 6. 운영 메모

- **업데이트**: `git pull && docker compose -f docker-compose.prod.yml up -d --build`. 마이그레이션은 기동 시 자동 적용됩니다.
- **백업**: `docker compose -f docker-compose.prod.yml exec postgres pg_dump -U <user> <db> > backup.sql`. 강의 파일은 `storage` 볼륨에 있습니다.
- **디스크**: 녹음은 WAV(16kHz 모노)라 시간당 약 115MB입니다. 정리 정책이 아직 없으니 사용량을 지켜봐야 합니다.
- **GPU 경합**: Whisper, bge-m3, LLM이 같은 GPU를 씁니다. 동시 분석이 많아지면 메모리가 부족할 수 있습니다.
- **비용**: GPU 인스턴스는 계속 켜 두면 월 수백 달러입니다. 쓰지 않을 때는 중지하세요.

## 7. 아직 준비되지 않은 것

- 자동 배포(CD)는 없습니다. CI는 테스트까지만 돌립니다
- 모니터링과 로그 수집 설정이 없습니다
- 컨테이너 헬스체크는 postgres에만 있습니다
- `ollama`를 별도 서버로 분리하려면 `LLM_BACKEND_URL`만 바꾸면 됩니다
