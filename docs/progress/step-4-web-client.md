# Step 4: Next.js `web-client` 스캐폴딩

- 기간: 2026-09-20
- 커밋: (검토 후 커밋 예정)
- 버전: **Next.js 16.3.5** (App Router, Turbopack), React 19.2.8, TypeScript 5.9.3, Tailwind CSS 4.3.3, ESLint 9, pnpm 12.4.2

## 구현한 것

```
web-client/
├── package.json                 # packageManager: pnpm@12.4.2
├── pnpm-lock.yaml, pnpm-workspace.yaml
├── next.config.ts, tsconfig.json (@/* 별칭), eslint.config.mjs, postcss.config.mjs
├── AGENTS.md, CLAUDE.md         # create-next-app이 만든 Next.js 16 에이전트 지침 (아래 참고)
├── app/
│   ├── layout.tsx               # lang="ko", 메타데이터 LectureMate AI
│   ├── page.tsx                 # 기본 홈 (보일러플레이트 제거)
│   ├── globals.css              # Tailwind v4 (@import "tailwindcss")
│   └── lectures/[id]/page.tsx   # 메인 강의 학습 대시보드 (SPEC §4.3)
├── components/                  # SPEC §4.3 컴포넌트 5개
│   ├── PdfViewer.tsx
│   ├── AnnotationOverlay.tsx
│   ├── AudioRecorder.tsx
│   ├── LectureChatPanel.tsx
│   └── SlideTimeline.tsx
└── types/api.ts                 # SPEC §2.1 API 계약 타입
```

생성 명령:
```bash
pnpm dlx create-next-app@16.3.5 web-client --ts --tailwind --eslint --app --no-src-dir \
  --no-react-compiler --import-alias "@/*" --use-pnpm --disable-git --yes
```
- `--no-src-dir`: SPEC이 `app/lectures/[id]/page.tsx`, `components/`를 프로젝트 루트 기준으로 적고 있어서 그대로 맞췄습니다.
- `--disable-git`: 이미 상위 저장소가 있어서 하위 git 저장소를 만들지 않게 했습니다.
- React Compiler: SPEC에 없어서 켜지 않았습니다.

## 코드 설명

### 화면 구성 (`app/lectures/[id]/page.tsx`)

```
┌──────────────────────────────────────────────────────────────┐
│ 강의 #101                         [녹음 시작] 실시간 자막 프리뷰 │ ← AudioRecorder
├────────────┬──────────────────────────────┬──────────────────┤
│ SlideTime- │ PdfViewer                    │ LectureChatPanel │
│ line       │  └ AnnotationOverlay (겹침)   │                  │
│ (12rem)    │ [이전] 5 [다음]               │ (22rem)          │
└────────────┴──────────────────────────────┴──────────────────┘
```

- 대시보드가 **현재 슬라이드 번호(`currentPage`)** 상태를 가지고, 세 컴포넌트가 이 상태를 공유합니다.
  - `SlideTimeline`에서 슬라이드를 누르면 `setCurrentPage`가 호출됩니다.
  - `PdfViewer`의 이전/다음 버튼도 `setCurrentPage`를 호출합니다.
  - `LectureChatPanel`의 출처 뱃지를 누르면 해당 슬라이드로 이동합니다. SPEC §4.3의 "뱃지 클릭 시 해당 슬라이드로 이동" 요구사항입니다.
- `lectureId`는 URL(`/lectures/101`)에서 `useParams`로 읽습니다.

### 컴포넌트 계약 (props)

| 컴포넌트 | props | 연결될 API (SPEC) | 구현 예정 (TODO) |
|---|---|---|---|
| `PdfViewer` | `pdfUrl`, `pageNumber`, `onPageChange`, `children` | §2.1-1 `pdfUrl` | pdfjs-dist로 로드하고 canvas에 렌더링 |
| `AnnotationOverlay` | `annotation: PageAnnotationResponse \| null`, `scale` | §2.1-4 | bbox를 화면 좌표로 변환해 하이라이트 그리기, 요약과 시험 힌트 표시 |
| `AudioRecorder` | `lectureId`, `onRecordingFinished?` | §2.1-2 WS, §2.1-3 | MediaRecorder 청크 전송, `TRANSCRIPT_PREVIEW` 표시, 종료 API 호출 |
| `LectureChatPanel` | `lectureId`, `onCitationClick` | §2.1-5 SSE | 토큰 스트리밍, 출처 뱃지 |
| `SlideTimeline` | `items: SlideTimelineItem[]`, `currentPage`, `onSelectPage` | (없음, 아래 미결 질문) | 발화 분량 막대, 시험 힌트 아이콘 |

- `AnnotationOverlay`는 `PdfViewer`의 `children`으로 들어가 PDF 페이지 위에 `absolute`로 겹쳐집니다. PDF 좌표(bbox)를 화면 픽셀로 바꾸려면 PdfViewer가 렌더링한 배율이 필요해서 `scale` prop을 두었습니다.
- 각 컴포넌트의 실제 로직은 `TODO` 주석으로 남겼고, 지금은 레이아웃과 상태 연결만 동작합니다.

### types/api.ts

SPEC §2.1의 요청/응답 JSON을 TypeScript 타입으로 옮긴 파일입니다. 목록은 SPEC 번호 순입니다.
- `LectureCreatedResponse`
- `TranscriptPreviewEvent`
- `RecordingFinishResponse`
- `PageAnnotationResponse`, `Highlight`, `BBox`
- `ChatRequest`
- `LectureStatus`: DB의 `lectures.status` 값과 같은 문자열 유니언

Spring Boot의 `HighlightBox` record(Step 3)와 `Highlight` 타입은 모양이 같습니다(`word`, `bbox`, `color`). 그래서 DB JSONB에서 API 응답을 거쳐 화면까지 같은 구조가 그대로 이어집니다.

## 다른 모듈과의 관계

```
web-client ──HTTP──▶ Spring Boot  /api/v1/lectures..., /pages/{n}/annotations, /chat (SSE)
web-client ◀──WS──▶ Spring Boot   /ws/v1/lectures/{id}/audio
```
- 프론트엔드는 **Spring Boot하고만 통신**합니다. FastAPI와 DB에는 직접 접근하지 않습니다(SPEC 아키텍처).

## 이렇게 한 이유

- **Next.js 16 (사용자 결정):** SPEC의 Next.js 14는 2025-10-26에 지원이 끝났고, 15.x도 2026-10-21에 끝납니다. 현재 지원 중인 16.x로 정했고, `SPEC.md`와 `AGENTS.md`도 16으로 바꿨습니다.
- **대시보드 페이지를 클라이언트 컴포넌트(`"use client"`)로 만든 이유:**
  - 여러 컴포넌트가 `currentPage` 상태와 콜백을 공유해야 합니다.
  - 서버 컴포넌트는 함수 prop을 클라이언트 컴포넌트에 넘길 수 없습니다. 그래서 상태를 가진 곳이 클라이언트여야 합니다.
  - 대안으로 서버 `page.tsx`와 별도의 클라이언트 래퍼 컴포넌트를 두는 방법도 있습니다. 하지만 SPEC §4.3에 없는 파일이 늘어나서 택하지 않았습니다.
  - 이 화면은 녹음, 채팅, PDF 조작 등 전부 인터랙티브해서 서버 렌더링으로 얻는 이점도 적습니다.
- **`types/api.ts` 추가:** SPEC §4.3 목록에는 없는 파일이지만, 내용은 SPEC §2.1 계약을 그대로 옮긴 것입니다. 컴포넌트마다 타입을 중복 정의하지 않으려고 추가했습니다.
- **PDF.js와 Fabric.js는 아직 설치하지 않음:** 뼈대 단계라 필요하지 않습니다. 또 SPEC이 오버레이를 "Fabric.js 또는 HTML5 Canvas"로 열어 두어서, 구현할 때 정하는 것이 맞습니다.
- **`create-next-app`이 만든 `AGENTS.md`와 `CLAUDE.md` 유지:** 설치된 Next.js 16 문서(`node_modules/next/dist/docs/`)를 먼저 읽으라는 지침입니다. `next dev`가 실행될 때마다 이 블록을 다시 쓰기 때문에 커밋해 두는 편이 깔끔합니다. 이번 작업에서도 이 지침에 따라 `PageProps`와 `useParams` 문서를 먼저 확인했습니다.
- **보일러플레이트 정리:** 기본 홈 화면(Vercel 안내)과 쓰이지 않는 `public/*.svg` 5개를 지웠습니다.

## 트러블슈팅

| 문제 | 원인 | 해결 |
|---|---|---|
| `pnpm: command not found` | pnpm 미설치 | 사용자 결정대로 corepack을 사용 |
| `corepack enable pnpm` 실패 (`EACCES: permission denied, symlink ... /usr/local/bin/pnpx`) | Node가 설치된 `/usr/local/bin`이 root 소유라 sudo 없이는 링크를 만들 수 없음 | `corepack enable --install-directory ~/.local/bin pnpm`. `~/.local/bin`은 이미 PATH에 있고 사용자 소유. pnpm 12.4.2가 설치되어 `package.json`의 `packageManager`로 버전 고정 |
| Next.js 14 지원 종료 | SPEC 작성 시점 이후 지원 기간 만료 | 사용자 승인을 받아 16.x 사용 |
| `pnpm-workspace.yaml`의 `allowBuilds: sharp: false` | pnpm은 기본적으로 의존성의 설치 스크립트를 막음 | 현재는 영향 없음. sharp는 미리 빌드된 바이너리(`@img/sharp-*`)로 동작. 나중에 이미지 최적화에 문제가 생기면 허용으로 변경 |

## 검증 결과

- `pnpm lint`: 오류 0
- `pnpm exec tsc --noEmit`: 타입 오류 0
- AGENTS.md의 "Any 타입 금지" 규칙: `const x: any`를 넣으면 ESLint가 `@typescript-eslint/no-explicit-any` **error**를 내는 것을 확인 (Next 기본 설정에 포함)
- `pnpm build`: 성공. `/`는 정적, `/lectures/[id]`는 동적 렌더링
- `pnpm dev` 후 `GET /lectures/101`: 200. 강의 제목, 녹음 버튼, 타임라인, PDF 영역, 채팅 영역이 모두 렌더링됨

## 사용법

```bash
cd web-client
pnpm install && pnpm dev      # http://localhost:3000/lectures/101
pnpm lint                     # ESLint
pnpm build                    # 프로덕션 빌드
```
