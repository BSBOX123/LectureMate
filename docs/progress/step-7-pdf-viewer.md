# Step 7: PDF.js 슬라이드 렌더링 + 테스트 DB 분리

- 기간: 2026-09-20
- 커밋: (검토 후 커밋 예정)
- 범위: `PdfViewer` 실제 렌더링, `AnnotationOverlay` 좌표 변환, `SlideTimeline` 페이지 목록, 브라우저 실검증, 테스트 DB 분리

## 구현한 것

### web-client
| 파일 | 내용 |
|---|---|
| `lib/pdf.ts` | `createPdfLoadingTask()`(동적 import + 워커 설정), `toOverlayRect()`(bbox → 화면 px), `clampPage()` |
| `components/PdfViewer.tsx` | PDF.js로 페이지를 canvas에 렌더링, 컨테이너 폭에 맞춰 배율 계산, 페이지 이동, 총 페이지 수/배율 콜백 |
| `components/AnnotationOverlay.tsx` | `highlights`의 bbox를 배율로 변환해 페이지 위에 하이라이트 표시 |
| `components/SlideTimeline.tsx` | 총 페이지 수만큼 목록 표시, 발화 분량 막대와 시험 힌트(★) 자리 |
| `app/lectures/[id]/page.tsx` | 총 페이지 수와 배율을 상태로 받아 타임라인·오버레이에 전달 |
| `lib/pdf.test.ts`, `vitest.config.ts` | 좌표 변환·페이지 보정 단위 테스트 5개 |

추가 의존성: `pdfjs-dist` 6.3.289, `vitest`(dev). `package.json`에 `"test": "vitest run"` 추가.

### server-core
- **CORS를 `/files/**`에도 적용** (아래 트러블슈팅 참고)
- **테스트 전용 DB 분리**: `src/test/resources/application-test.yml`, `build.gradle`의 `systemProperty 'spring.profiles.active', 'test'`

### 인프라
- `db/init-test-db.sql`: `lecturemate_test` 데이터베이스를 만들고 `01-init.sql` 스키마를 그대로 적용
- `docker-compose.yml`: 위 스크립트를 `02-test-db.sql`로 마운트
- `ai-engine/tests/conftest.py`: 테스트에서 `DATABASE_URL`을 `lecturemate_test`로 지정

## 코드 설명

### PDF 렌더링 흐름

```
대시보드 ──GET /api/v1/lectures/{id}──▶ pdfUrl
        ──fetch + Bearer──▶ blob URL
PdfViewer ──createPdfLoadingTask(blobUrl)──▶ PDF.js 워커
          ──getPage(n) → getViewport({scale}) → page.render(canvas)
          ──onDocumentLoaded(총 페이지) ──▶ SlideTimeline
          ──onPageRendered({scale}) ──▶ AnnotationOverlay (bbox 변환용)
```

- **배율:** `scale = 컨테이너 폭 / 페이지 원본 폭`. `ResizeObserver`로 창 크기 변화에 맞춰 다시 그립니다.
- **선명도:** `devicePixelRatio`만큼 캔버스 픽셀을 키우고 CSS 크기는 논리 픽셀로 둡니다.
- **좌표계:** PyMuPDF와 PDF.js 기본 뷰포트 모두 좌상단 원점이라 y를 뒤집지 않아도 됩니다. 그래서 `toOverlayRect()`는 bbox에 배율만 곱합니다.
- **정리:** `pdfUrl`이 바뀌거나 언마운트되면 loading task를 `destroy()`해 워커까지 정리합니다. `PDFDocumentProxy`에는 `destroy()`가 없습니다.

### 테스트 DB 분리

| | 개발용 | 테스트용 |
|---|---|---|
| DB | `lecturemate` | `lecturemate_test` |
| Spring Boot | `application.yml` | `application-test.yml` (test 프로필, Gradle이 자동 지정) |
| FastAPI | `.env` | `tests/conftest.py`가 `DATABASE_URL` 지정 |

## 이렇게 한 이유

- **PDF.js를 동적 import:** 브라우저 전용 라이브러리라 서버 렌더링 단계에서 불러오면 안 됩니다. 워커 경로는 `new URL("pdfjs-dist/build/pdf.worker.min.mjs", import.meta.url)`로 잡아 번들러가 파일을 함께 내보내게 했습니다.
- **`SlideTimeline`에 `totalPages` 추가:** 분석 전에도 페이지 목록으로 이동할 수 있어야 합니다. 분석 결과(`items`)가 없으면 막대는 비어 있고 ★도 표시되지 않습니다. 가짜 데이터를 만들지 않았습니다.
- **Vitest 추가:** `AGENTS.md`에 프론트엔드 테스트 명령이 없었는데, 좌표 변환처럼 눈으로 확인하기 어려운 순수 함수는 테스트가 필요합니다. Next.js 프로젝트에서 표준적인 선택입니다.
- **`AnnotationOverlay`를 미리 구현:** §2.1-4 주석 API는 아직 없지만, 좌표 변환 규칙(`bbox × scale`)을 지금 고정해 두면 데이터가 생겼을 때 바로 붙습니다.

## 트러블슈팅

| 문제 | 원인 | 해결 |
|---|---|---|
| **브라우저에서 PDF가 안 뜸 (CORS)** | CORS 설정을 `/api/**`에만 등록했는데 PDF는 `/files/pdf/{id}.pdf`에서 받는다. Authorization 헤더 때문에 preflight가 발생하고 차단됨 | `/files/**`에도 같은 CORS 설정 등록. **curl은 CORS를 따지지 않아 Step 6 검증에서 드러나지 않았다** |
| 페이지 이동 버튼이 화면 밖으로 밀림 | flex 자식의 기본 `min-height: auto` 때문에 캔버스 높이만큼 늘어남 | 뷰어 섹션과 스크롤 컨테이너에 `min-h-0` 추가 |
| `PDFDocumentProxy.destroy()` 타입 오류 | PDF.js 6에서는 loading task에만 `destroy()`가 있음 | `getDocument()`가 준 loading task를 보관해 정리 |
| ESLint `react-hooks/set-state-in-effect` | effect 본문에서 상태 초기화 | 초기화를 제거하고 로딩 결과 콜백에서만 상태를 갱신 |
| **테스트 실행이 개발용 DB 데이터를 삭제** | 통합 테스트가 `userRepository.deleteAll()`을 호출하는데 개발용 DB를 그대로 사용 | `lecturemate_test` DB를 만들어 테스트만 그쪽을 보게 분리 |
| 테스트용 `application.yml`을 만들자 컨텍스트 로딩 실패 | 같은 이름의 파일은 병합되지 않고 메인 설정을 통째로 덮어씀 (datasource 계정 등 유실) | `application-test.yml`(프로필 파일)로 바꾸고 Gradle에서 `spring.profiles.active=test` 지정 |

## 검증 결과

**자동 테스트**
- Spring Boot 15개 통과 (전용 DB 사용)
- FastAPI 5개 통과 (전용 DB 사용)
- web-client Vitest 5개 통과, `pnpm lint`·`tsc --noEmit`·`pnpm build` 통과

**실제 브라우저 검증** (헤드리스 Chrome, 서버 3개 + DB 모두 기동)

스크래치패드에 puppeteer-core 스크립트를 만들어 다음 과정을 실행했습니다.

| 단계 | 결과 |
|---|---|
| 회원가입 → 자동 로그인 | 홈에 이름·이메일 표시 |
| PDF 업로드 | `/lectures/23`으로 이동 |
| PDF.js 렌더링 | canvas 862×1219 픽셀, 실제 잉크 픽셀 2314개 검출 |
| 강의 상태 | `READY` |
| 타임라인 | 버튼 3개 (PDF 3페이지) |
| 페이지 표시 | `1 / 3`, 이동 버튼이 화면 안에 보임 |
| 타임라인으로 3페이지 이동 | `3 / 3`으로 바뀌고 다시 렌더링됨 |
| 브라우저 콘솔 | 첫 화면의 `/auth/refresh` 401 두 건만 (쿠키 없는 상태의 세션 복구 시도, 정상 동작) |

**데이터 정리:** 검증에 사용한 계정·강의·업로드 파일은 모두 삭제했습니다. 개발용 DB에 더미 행을 넣고 전체 테스트를 돌려 **데이터가 보존되는 것**도 확인했습니다.

## 사용법

```bash
cd web-client
pnpm test          # Vitest
pnpm dev           # http://localhost:3000
```

테스트 DB는 postgres 볼륨을 처음 만들 때 함께 생성됩니다. 기존 볼륨이 있다면 `docker compose down -v && docker compose up -d postgres`가 필요합니다.
