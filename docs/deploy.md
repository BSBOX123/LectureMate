# 배포 가이드

> **현재 선택한 구성: A안 — 전부 로컬 (2026-09-20 확정)**
>
> EC2 배포는 하지 않습니다. 개발도 시연도 Mac 한 대에서 실행합니다.
> AI 모델은 개발자 Mac에서 돌립니다. 측정 결과 Mac 네이티브 Ollama가 9.1 tok/s(슬라이드 1장 18초)로 실용적인 반면, GPU EC2는 시간당 1.2~1.5달러가 듭니다. 아래 "부록: 로컬 우선 구성"을 먼저 보세요.
> GPU EC2 구성은 공개 서비스로 확장할 때 쓰는 계획입니다.

## (계획) GPU EC2 1대 구성

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


---

# 부록: 로컬 우선 구성 (현재 방식)

## A. 전부 로컬 (현재 방식, 확정)

```bash
brew services start ollama          # Mac GPU(Metal) 사용
docker-compose up -d postgres
cd ai-engine && LLM_MODEL_NAME=qwen2.5:7b-instruct uvicorn main:app --port 8000
cd server-core && ./gradlew bootRun
cd web-client && pnpm dev           # http://localhost:3000
```

제약:
- **모델은 7B를 씁니다.** Mac RAM 16GB에서 14B(약 9GB) + Whisper + bge-m3 + DB + 서버 2개는 빠듯합니다. SPEC 기본값(`qwen2.5:14b-instruct`)과 다르므로 실행 시 `LLM_MODEL_NAME`으로 지정합니다.
- **Whisper는 CPU로 돕니다.** faster-whisper(CTranslate2)는 Metal을 지원하지 않습니다. 실시간 프리뷰(base)는 충분하지만, 1시간 강의를 large-v3로 정밀 전사하면 수십 분 걸릴 수 있습니다. 필요하면 `whisper.cpp`나 `mlx-whisper`처럼 Metal을 쓰는 백엔드로 교체를 검토합니다.

## B. 앱만 EC2, AI는 Mac (아직 사용하지 않음 — 외부 공개가 필요해지면)

EC2에는 Postgres + Spring Boot + Next.js + Nginx만 올리고, AI 엔진은 Mac에서 실행한 뒤 **Mac이 EC2로 역터널을 엽니다.** 공유기 포트포워딩이 필요 없습니다.

```bash
# Mac에서 실행 (터널 유지)
ssh -N -R 8000:localhost:8000 -L 5432:localhost:5432 \
    -i ~/.ssh/keys/LectureMate.pem ubuntu@<EC2_IP>
```

- `-R 8000`: EC2의 localhost:8000 → Mac의 FastAPI. Spring Boot는 `FASTAPI_ENGINE_URL=http://localhost:8000` 그대로 씁니다.
- `-L 5432`: Mac의 localhost:5432 → EC2의 Postgres. FastAPI가 DB에 직접 적재할 수 있습니다.
- **Mac이 꺼지거나 잠들면 AI 기능이 멈춥니다.** `caffeinate -s`로 잠들지 않게 할 수 있습니다.
- 이 구성에서는 EC2가 t3.small(2GB) 이상이어야 합니다. 현재 `t3.micro`(1GB)로는 부족합니다.

## C. 현재 EC2 상태 (2026-09-20)

| 항목 | 값 |
|---|---|
| 인스턴스 | `i-0f00378275d7141f3` (LectureMate), t3.micro, 20GB gp3, ap-northeast-2c |
| 보안 그룹 | `sg-0a6718a62c9250f47` — **22번을 0.0.0.0/0 → 내 IP(/32)로 제한 완료** |
| 탄력적 IP | 미할당 (재부팅 시 공인 IP 변경됨) |
| 키 | `~/.ssh/keys/LectureMate.pem` (권한 600으로 수정 완료) |

집이나 학교 등 네트워크가 바뀌면 현재 IP로 규칙을 다시 추가해야 합니다.

```bash
MYIP=$(curl -s https://checkip.amazonaws.com)
aws ec2 authorize-security-group-ingress --group-id sg-0a6718a62c9250f47 \
  --ip-permissions "IpProtocol=tcp,FromPort=22,ToPort=22,IpRanges=[{CidrIp=$MYIP/32,Description=my-ip-ssh}]"
```
