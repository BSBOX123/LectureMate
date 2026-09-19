# Project Guidelines for Claude Code

## Overview
이 프로젝트는 SPEC.md 명세서를 기반으로 구축됩니다. 
작업을 시작하기 전 항상 `SPEC.md`를 읽고 정의된 아키텍처 및 DTO 규격을 준수하세요.

## Infrastructure (Docker Compose)
- Working Directory: 프로젝트 루트
- Run: `docker-compose up -d postgres ollama`
- DB 초기 스키마: `db/init.sql` (postgres 볼륨 최초 생성 시 1회 실행, 재적용 시 `docker compose down -v`)

## Build & Test Commands by Module

### 1. Backend (Spring Boot)
- Working Directory: `./server-core`
- Run: `./gradlew bootRun`
- Test: `./gradlew test`
- Build: `./gradlew build`
- Style: Java 21, Spring Boot 3.3.x, Google Java Style 준수

### 2. Frontend (Next.js / TypeScript)
- Working Directory: `./web-client`
- Install & Run: `pnpm install && pnpm dev`
- Build: `pnpm build`
- Style: Next.js 14 App Router, Tailwind CSS, Any 타입 금지, Functional Component 사용

### 3. AI Engine (Python / FastAPI)
- Working Directory: `./ai-engine`
- Run: `source .venv/bin/activate && uvicorn main:app --host 0.0.0.0 --port 8000 --reload`
- Test: `pytest`
- Style: Python 3.11+, PEP8 준수, Type Hinting 필수, Pydantic v2 사용

## Safety Rules
- `/data/raw/` 경로의 원본 데이터 파일은 절대 수정/삭제하지 말 것.
- `SPEC.md`에 정의되지 않은 임의의 API 엔드포인트 변경 금지.
