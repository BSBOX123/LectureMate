# Step 5: 인증 (Spring Security + JWT)

- 기간: 2026-09-20
- 커밋: (검토 후 커밋 예정)
- 범위: SPEC 인증 API 정의 → DB 스키마 → Spring Boot 구현 → web-client 로그인/회원가입 화면

## 구현한 것

### SPEC 및 DB
- `SPEC.md` §2.1에 인증 API 5개 추가 (6~10번), 인증 규칙 명시
- `SPEC.md` §2.2에 내부 API 인증(`X-Internal-Secret`) 규칙 추가
- `SPEC.md` §3, `db/init.sql`에 `refresh_tokens` 테이블 추가
- `SPEC.md` §5.1에 `JWT_SECRET`, `INTERNAL_API_SECRET`, `WEB_CLIENT_ORIGIN` 추가

| 엔드포인트 | 설명 | 인증 |
|---|---|---|
| `POST /api/v1/auth/signup` | 회원가입 (201, 중복 409, 형식 오류 400) | 불필요 |
| `POST /api/v1/auth/login` | 로그인 → Access Token + Refresh 쿠키 | 불필요 |
| `POST /api/v1/auth/refresh` | 쿠키로 Access Token 재발급 (회전) | 쿠키 |
| `POST /api/v1/auth/logout` | Refresh Token 폐기 (204) | 쿠키 |
| `GET /api/v1/users/me` | 내 정보 | Bearer |

### Spring Boot (`server-core`)
```
config/
├── SecurityConfig.java        # 필터 체인 2개, JWT 인코더/디코더, BCrypt, CORS
├── AuthProperties.java        # lecturemate.auth.*
└── InternalApiProperties.java # lecturemate.internal.secret
security/
├── JwtTokenService.java       # Access Token(HS256) 발급
└── InternalSecretFilter.java  # X-Internal-Secret 검사
domain/entity/RefreshToken.java
repository/{UserRepository,RefreshTokenRepository}.java
service/AuthService.java       # 가입/로그인/재발급/로그아웃
api/
├── controller/{AuthController,UserController}.java
├── dto/{SignupRequest,LoginRequest,TokenResponse,UserResponse}.java
└── ApiExceptionHandler.java   # ProblemDetail(409/401)
```
추가 의존성: `spring-boot-starter-security`, `-security-oauth2-resource-server`, `-validation` (및 대응 test starter)

### web-client
```
lib/api.ts                  # fetch 래퍼, Access Token 메모리 보관, ProblemDetail 파싱
components/AuthProvider.tsx # 인증 컨텍스트, 새로고침 시 세션 복구
types/auth.ts               # 인증 API 계약 타입
app/login/page.tsx, app/signup/page.tsx
app/page.tsx                # 로그인 상태에 따라 로그인/회원가입 링크 또는 내 정보 + 로그아웃
.env.example                # NEXT_PUBLIC_API_BASE_URL
```

## 코드 설명

### 토큰 흐름

```
① 로그인   Client ──{email,password}──▶ Spring Boot
          Client ◀─ accessToken(30분, 본문) + refreshToken(14일, httpOnly 쿠키) ─┘

② API 호출 Client ──Authorization: Bearer {accessToken}──▶ /api/v1/**

③ 재발급   Client ──쿠키만──▶ /api/v1/auth/refresh
          기존 Refresh Token 폐기(revoked_at) → 새 Access + 새 Refresh 발급 (회전)

④ 로그아웃 해당 사용자의 살아있는 Refresh Token 전부 폐기 + 쿠키 만료
```

- **Access Token:** HS256 JWT, `sub`에 userId, `email` 클레임, 30분. 서버는 상태를 저장하지 않고 서명만 검증합니다.
- **Refresh Token:** 32바이트 난수(Base64url). **DB에는 원문이 아니라 SHA-256 해시를 저장**합니다. DB가 유출돼도 토큰을 그대로 쓸 수 없습니다.
- **회전(rotation):** 재발급할 때마다 이전 토큰을 폐기합니다. 탈취된 토큰이 오래 쓰이지 못합니다.
- **쿠키 속성:** `HttpOnly`, `Path=/api/v1/auth`, `SameSite=Lax`, `Max-Age=14일`. 운영(HTTPS)에서는 `AUTH_REFRESH_COOKIE_SECURE=true`로 `Secure`를 켭니다.

### SecurityConfig의 필터 체인 2개

| 체인 | 대상 | 인증 방식 |
|---|---|---|
| `internalFilterChain` (Order 1) | `/internal/**` | `X-Internal-Secret` 헤더만 검사 (JWT 미적용) |
| `apiFilterChain` | 그 외 전체 | `/api/v1/auth/**`, `/error`, `OPTIONS`는 허용, 나머지는 Bearer 토큰 필요 |

- 세션을 쓰지 않습니다(`STATELESS`). CSRF는 쿠키 기반 폼 인증이 아니므로 끕니다. Refresh 쿠키는 `SameSite=Lax`와 경로 제한으로 보호합니다.
- CORS는 `/api/**`에만 적용하고 `allowCredentials=true`로 설정해 쿠키를 주고받습니다. 허용 Origin은 `WEB_CLIENT_ORIGIN`입니다.
- `InternalSecretFilter`는 `MessageDigest.isEqual`로 상수 시간 비교를 합니다. 문자열 `equals`는 앞부분부터 비교해 시간 차이로 값을 추측당할 수 있습니다.

### 프론트엔드 세션 관리

- **Access Token은 메모리(모듈 변수)에만 둡니다.** `localStorage`에 두면 XSS로 탈취될 수 있습니다.
- 새로고침하면 메모리가 비므로 `AuthProvider`가 마운트될 때 `POST /auth/refresh`를 호출해 세션을 복구합니다. 쿠키가 없거나 만료면 비로그인 상태로 둡니다.
- 모든 요청은 `credentials: "include"`로 보냅니다. 쿠키를 함께 보내기 위해서입니다.

### 다른 모듈과의 관계

- `users` ← `refresh_tokens` (`ON DELETE CASCADE`). 회원을 지우면 토큰도 함께 지워집니다.
- `Lecture.user`(Step 3)가 토큰의 userId와 연결됩니다. 강의 API를 구현할 때 소유자 검사를 넣습니다(SPEC 인증 규칙: 불일치 시 404).
- FastAPI는 Webhook 호출 시 `X-Internal-Secret` 헤더를 붙여야 합니다. `ai-engine`에는 아직 반영하지 않았습니다(Webhook 호출 코드 자체가 없음).

## 이렇게 한 이유

- **Access + Refresh 방식 (사용자 결정):** Access Token 수명이 짧아 탈취돼도 피해가 제한되고, Refresh Token은 JS가 읽을 수 없는 httpOnly 쿠키에 둡니다.
- **Refresh Token을 DB에 저장:** 로그아웃과 회전이 실제로 동작하려면 서버가 토큰을 폐기할 수 있어야 합니다. JWT만 쓰면 만료 전까지 취소할 수 없습니다.
- **JWT 라이브러리 대신 Spring Security 내장 기능:** `spring-boot-starter-security-oauth2-resource-server`의 `NimbusJwtEncoder`/`Decoder`를 씁니다. 외부 라이브러리(jjwt 등)를 추가하지 않아 버전 관리가 단순합니다.
- **`ProblemDetail`(RFC 9457) 오류 응답:** Spring 기본 형식이라 별도 DTO가 필요 없습니다. 프론트는 `detail` 필드를 그대로 화면에 보여줍니다.
- **`/ws/v1/**` 인증은 미구현:** WebSocket 핸들러 자체가 아직 없습니다. 브라우저는 WebSocket 요청에 헤더를 붙일 수 없어서, 핸들러를 만들 때 토큰 전달 방식(쿼리 파라미터 등)을 함께 정해야 합니다.

## 트러블슈팅

| 문제 | 원인 | 해결 |
|---|---|---|
| 테스트 컴파일 실패: `package com.fasterxml.jackson.databind does not exist` | Spring Boot 4는 Jackson 3를 쓰고 패키지가 `tools.jackson.*`으로 바뀜 | import를 `tools.jackson.databind.JsonNode`로 변경. Jackson 3에서는 `asText()`도 `asString()`으로 바뀜 |
| `EntityMappingTest` 실패 (`users_email_key` 중복) | `@SpringBootTest`인 `AuthFlowTest`는 트랜잭션 롤백이 없어 데이터가 남고, 다른 테스트와 이메일이 겹침 | `AuthFlowTest`에 `@BeforeEach`/`@AfterEach` 정리 추가, 테스트 이메일을 분리 |
| **시크릿이 맞는데도 내부 Webhook이 401** | 핸들러가 없어 발생한 404가 `/error`로 forward되고, `/error`는 인증이 필요해 다시 401이 됨. **MockMvc는 `/error` forward를 하지 않아 테스트에서는 404로 보여 드러나지 않음** | `apiFilterChain`에서 `/error`를 `permitAll`로 변경. 실제 서버 curl로 재확인 |

> 세 번째 항목은 "테스트는 통과하는데 실제 서버 동작은 다른" 경우였습니다. 그래서 이후에도 인증 관련 변경은 실행 중인 서버에 직접 요청해 확인하는 편이 좋습니다.

## 검증 결과

**Spring Boot 테스트: 10개 통과** (`./gradlew test`, 로컬 postgres 필요)
- `AuthFlowTest` 7개: 가입→로그인→내 정보, 이메일 중복 409, 잘못된 비밀번호 401, 형식 오류 400, 토큰 없음/잘못된 토큰 401, 재발급 회전 및 이전 토큰 차단, 로그아웃 후 재발급 차단, 내부 Webhook 시크릿 검사
- 기존 `EntityMappingTest` 2개, `LectureMateApplicationTests` 1개도 계속 통과

**실행 중인 서버 대상 확인** (`bootRun` + curl)

| 항목 | 결과 |
|---|---|
| 회원가입 | 201, 사용자 생성 |
| 토큰 없이 `/users/me` | 401 |
| 로그인 | 200, `refreshToken` 쿠키(HttpOnly, Path=/api/v1/auth, SameSite=Lax, 14일) |
| 토큰으로 `/users/me` | 200 |
| 재발급 | 200, 쿠키 값 교체 |
| 회전된 옛 쿠키 재사용 | 401 |
| 로그아웃 → 재발급 | 204 → 401 |
| 내부 Webhook (없음/틀림/맞음) | 401 / 401 / 404(필터 통과, 컨트롤러 미구현) |
| CORS preflight + 실제 요청 | `Access-Control-Allow-Origin: http://localhost:3000`, `Allow-Credentials: true` |

**web-client**: `pnpm lint`, `pnpm exec tsc --noEmit`, `pnpm build` 모두 통과. `/login`, `/signup` 200 응답.

**확인하지 못한 것:** 브라우저 확장이 연결되어 있지 않아 화면에서 직접 클릭하는 테스트는 못 했습니다. 대신 브라우저와 같은 조건(Origin 헤더 + 쿠키 저장소)으로 curl 요청을 보내 확인했습니다.

검증에 사용한 계정 2개는 DB에서 삭제했습니다.

## 사용법

```bash
docker compose up -d postgres
cd server-core && ./gradlew bootRun     # :8080
cd web-client && pnpm dev               # :3000 → http://localhost:3000/signup
```

운영 배포 시 반드시 설정할 환경 변수:
```bash
JWT_SECRET=<32바이트 이상 랜덤 값>
INTERNAL_API_SECRET=<랜덤 값, FastAPI와 동일>
AUTH_REFRESH_COOKIE_SECURE=true
WEB_CLIENT_ORIGIN=https://<프론트 도메인>
```
