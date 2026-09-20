# Project Guidelines for Claude Code

## Overview
이 프로젝트는 SPEC.md 명세서를 기반으로 구축됩니다. 
작업을 시작하기 전 항상 `SPEC.md`를 읽고 정의된 아키텍처 및 DTO 규격을 준수하세요.

## Infrastructure (Docker Compose)
- Working Directory: 프로젝트 루트
- Run: `docker-compose up -d postgres ollama`
- DB 스키마: Flyway 가 소유한다 (`server-core/src/main/resources/db/migration/`). 기동 시 자동 적용.
- 스키마 변경은 기존 마이그레이션 파일 수정 금지. 새 파일 `V{n}__설명.sql` 을 추가할 것 (볼륨 삭제 불필요).
- 통합 테스트는 전용 DB `lecturemate_test` 를 사용한다 (`db/init-test-db.sql`). 개발용 DB 를 가리키게 바꾸지 말 것 — 테스트가 데이터를 삭제한다.

## Build & Test Commands by Module

### 1. Backend (Spring Boot)
- Working Directory: `./server-core`
- Run: `./gradlew bootRun`
- Test: `./gradlew test`
- Build: `./gradlew build`
- Style: Java 21, Spring Boot 4.1.x, Google Java Style 준수

### 2. Frontend (Next.js / TypeScript)
- Working Directory: `./web-client`
- Install & Run: `pnpm install && pnpm dev`
- Test: `pnpm test` (Vitest)
- Typecheck: `pnpm typecheck` (next typegen + tsc)
- Build: `pnpm build`
- Style: Next.js 16 App Router, Tailwind CSS, Any 타입 금지, Functional Component 사용

### 3. AI Engine (Python / FastAPI)
- Working Directory: `./ai-engine`
- Run: `source .venv/bin/activate && uvicorn main:app --host 0.0.0.0 --port 8000 --reload`
- Test: `pytest`
- Style: Python 3.11+, PEP8 준수, Type Hinting 필수, Pydantic v2 사용

## Progress Documentation
- 모든 작업마다 `docs/progress/`에 진행 기록을 남길 것 (구현 기능, 코드 설명, 엔티티/모듈 관계, 결정 이유, 트러블슈팅).
- 작업 후 `docs/progress/README.md`의 진행 현황, 결정 기록, 미결 질문을 갱신할 것.

## Safety Rules
- `/data/raw/` 경로의 원본 데이터 파일은 절대 수정/삭제하지 말 것.
- `SPEC.md`에 정의되지 않은 임의의 API 엔드포인트 변경 금지.
