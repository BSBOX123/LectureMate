#!/bin/bash
# LectureMate 전체 서비스 시작/중지 (A안: 전부 로컬 실행)
#   scripts/lecturemate.sh start|stop|status
# Desktop 의 LectureMate.app 이 이 스크립트를 호출한다.

set -uo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$HOME/Library/Logs/LectureMate"
WEB_URL="http://localhost:3000"

# GUI 에서 실행하면 PATH 가 최소라 Homebrew 와 사용자 bin 을 직접 추가한다
export PATH="/opt/homebrew/bin:/usr/local/bin:$HOME/.local/bin:$HOME/.orbstack/bin:$PATH"

mkdir -p "$LOG_DIR"

say() { echo "$(date '+%H:%M:%S') $*"; }

port_pid() { lsof -tiTCP:"$1" -sTCP:LISTEN 2>/dev/null | head -1; }

wait_for() { # wait_for <포트> <이름> <최대 초>
  local port=$1 name=$2 limit=${3:-90} waited=0
  while [ "$waited" -lt "$limit" ]; do
    [ -n "$(port_pid "$port")" ] && { say "✓ $name 준비됨 (:$port)"; return 0; }
    sleep 1
    waited=$((waited + 1))
  done
  say "✗ $name 이 $limit 초 안에 뜨지 않았습니다. 로그: $LOG_DIR/$name.log"
  return 1
}

ensure_docker() { # OrbStack 이 꺼져 있으면 켜고 도커 데몬이 응답할 때까지 기다린다
  if docker ps >/dev/null 2>&1; then
    return 0
  fi
  say "OrbStack 시작..."
  open -a OrbStack 2>/dev/null || { say "✗ OrbStack 을 찾지 못했습니다"; return 1; }
  local waited=0
  while [ "$waited" -lt 60 ]; do
    docker ps >/dev/null 2>&1 && { say "✓ Docker 준비됨"; return 0; }
    sleep 2
    waited=$((waited + 2))
  done
  say "✗ OrbStack 이 60초 안에 준비되지 않았습니다"
  return 1
}

wait_for_postgres() {
  local waited=0
  while [ "$waited" -lt 60 ]; do
    if [ "$(docker inspect -f '{{.State.Health.Status}}' lecturemate-postgres 2>/dev/null)" = "healthy" ]; then
      say "✓ PostgreSQL 준비됨 (:5432)"
      return 0
    fi
    sleep 2
    waited=$((waited + 2))
  done
  say "✗ PostgreSQL 이 60초 안에 준비되지 않았습니다. 로그: $LOG_DIR/postgres.log"
  return 1
}

start() {
  say "LectureMate 시작 (로그: $LOG_DIR)"

  # 1. Ollama (LLM) — Homebrew 서비스
  if ! curl -sf http://localhost:11434/api/version >/dev/null 2>&1; then
    say "Ollama 시작..."
    brew services start ollama >/dev/null 2>&1 || nohup ollama serve >"$LOG_DIR/ollama.log" 2>&1 &
  fi

  # 2. PostgreSQL — Docker 컨테이너 (OrbStack 이 꺼져 있으면 먼저 켠다)
  ensure_docker || return 1
  if ! docker ps --format '{{.Names}}' 2>/dev/null | grep -q lecturemate-postgres; then
    say "PostgreSQL 컨테이너 시작..."
    (cd "$PROJECT_DIR" && docker-compose up -d postgres) >"$LOG_DIR/postgres.log" 2>&1
  fi
  # DB 가 연결을 받기 전에 백엔드가 뜨면 기동에 실패한다
  wait_for_postgres

  # 3. FastAPI (AI 엔진)
  if [ -z "$(port_pid 8000)" ]; then
    say "AI 엔진 시작..."
    (cd "$PROJECT_DIR/ai-engine" && \
      LLM_MODEL_NAME="${LLM_MODEL_NAME:-qwen2.5:7b-instruct}" \
      nohup .venv/bin/uvicorn main:app --host 127.0.0.1 --port 8000 \
        >"$LOG_DIR/ai-engine.log" 2>&1 &)
  fi

  # 4. Spring Boot (백엔드)
  if [ -z "$(port_pid 8080)" ]; then
    say "백엔드 시작..."
    (cd "$PROJECT_DIR/server-core" && nohup ./gradlew bootRun \
      >"$LOG_DIR/server-core.log" 2>&1 &)
  fi

  # 5. Next.js (프론트엔드)
  if [ -z "$(port_pid 3000)" ]; then
    say "프론트엔드 시작..."
    (cd "$PROJECT_DIR/web-client" && nohup pnpm dev >"$LOG_DIR/web-client.log" 2>&1 &)
  fi

  wait_for 11434 ollama 60
  wait_for 8000 ai-engine 90
  wait_for 8080 server-core 180
  wait_for 3000 web-client 90 && {
    say "브라우저 열기: $WEB_URL"
    open "$WEB_URL"
  }
}

stop() {
  say "LectureMate 중지"
  for port in 3000 8080 8000; do
    pid=$(port_pid "$port")
    [ -n "$pid" ] && { kill "$pid" 2>/dev/null; say "포트 $port 프로세스 종료 ($pid)"; }
  done
  # gradlew bootRun 이 남긴 자식 프로세스 정리
  pkill -f "com.lecturemate.LectureMateApplication" 2>/dev/null
  (cd "$PROJECT_DIR" && docker-compose stop postgres) >/dev/null 2>&1
  say "완료 (Ollama 는 그대로 둡니다: brew services stop ollama)"
}

status() {
  for entry in "11434:Ollama" "5432:PostgreSQL" "8000:AI 엔진" "8080:백엔드" "3000:프론트엔드"; do
    port=${entry%%:*}; name=${entry#*:}
    if [ -n "$(port_pid "$port")" ]; then echo "● $name (:$port) 실행 중"; else echo "○ $name (:$port) 중지됨"; fi
  done
}

case "${1:-start}" in
  start) start ;;
  stop) stop ;;
  status) status ;;
  *) echo "사용법: $0 start|stop|status"; exit 1 ;;
esac
