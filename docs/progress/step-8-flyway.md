# Step 8: Flyway 도입 (DB 마이그레이션)

- 기간: 2026-09-20
- 커밋: (검토 후 커밋 예정)
- 목적: 스키마를 바꿀 때 **볼륨을 지우지 않아도** 되게 만든다. 데이터가 쌓이기 전에 마이그레이션 체계를 갖춘다.

## 무엇이 달라졌나

| | 이전 | 이후 |
|---|---|---|
| 스키마 소유 | `db/init.sql` (docker 최초 기동 시 1회) | Flyway 마이그레이션 (`server-core/src/main/resources/db/migration/`) |
| 스키마 변경 | `docker compose down -v` → **데이터 전부 삭제** | 새 `V{n}__설명.sql` 추가 → 기동 시 자동 적용, 데이터 보존 |
| 적용 이력 | 없음 | `flyway_schema_history` 테이블로 추적 |

## 구현한 것

```
server-core/src/main/resources/db/migration/
├── V1__init_schema.sql          # 기존 db/init.sql 을 그대로 이동 (git mv)
└── V2__lectures_user_index.sql  # lectures(user_id) 인덱스 추가
```

- **의존성:** `spring-boot-starter-flyway`, `org.flywaydb:flyway-database-postgresql`
- **설정** (`application.yml`)
  ```yaml
  spring.flyway.enabled: true
  spring.flyway.baseline-on-migrate: true
  spring.flyway.baseline-version: 1
  ```
- **docker-compose:** 최초 기동용 스크립트를 `db/init.sql` 대신 `V1__init_schema.sql` 을 그대로 마운트하도록 변경. 파일이 하나뿐이라 스키마 정의가 갈라지지 않습니다.
- **SPEC §3, AGENTS.md:** 스키마의 주인이 Flyway이며 기존 마이그레이션 파일은 수정하지 않는다는 규칙을 명시

### V2 마이그레이션 내용

```sql
CREATE INDEX IF NOT EXISTS idx_lectures_user ON lectures(user_id);
```

`GET /api/v1/lectures`는 `user_id`로 필터하는데, PostgreSQL은 외래키 컬럼에 인덱스를 자동 생성하지 않아 전체 스캔이 발생하고 있었습니다. 진행 상황 점검에서 발견해 이번 마이그레이션으로 해결했습니다.

## 이렇게 한 이유

- **`baseline-on-migrate: true`인 이유:** 이미 V1 스키마가 들어 있는 DB(로컬 개발용, 테스트용)가 있습니다. Flyway는 기본적으로 비어 있지 않은 DB를 거부합니다. baseline을 켜면 그런 DB를 "V1까지 적용됨"으로 기록하고 V2부터 진행합니다.
- **`V1__init_schema.sql`을 docker 초기화 스크립트로도 쓰는 이유:** 파일을 복제하면 두 정의가 언젠가 어긋납니다. 같은 파일을 마운트하면 그럴 일이 없습니다.
- **`ddl-auto: validate`는 그대로 둡니다.** 스키마는 Flyway가 만들고, Hibernate는 엔티티와 맞는지 검사만 합니다. 역할이 겹치지 않습니다.
- **FastAPI는 스키마를 만들지 않습니다.** 기존과 동일하게 조회·적재만 합니다. 마이그레이션은 Spring Boot 기동 시 적용됩니다.

## 트러블슈팅

| 문제 | 원인 | 해결 |
|---|---|---|
| `flyway-core`를 추가했는데 마이그레이션이 실행되지 않음 (로그에 Flyway 흔적 없음, 인덱스 생성 안 됨) | Spring Boot 4에서는 자동 설정이 모듈로 분리됨. `flyway-core`만 넣으면 Boot가 Flyway를 구성하지 않음 | `spring-boot-starter-flyway`(+ 테스트용 `-flyway-test`)로 교체 |
| `psql -c "drop database ..."` 실패 (`cannot run inside a transaction block`) | `psql -c` 여러 개를 이어 쓰면 트랜잭션으로 묶임 | 명령을 나눠 실행 |

## 검증 결과

**① 기존 DB (스키마 있음, 이력 없음)**
```
Creating Schema History table with baseline ...
Successfully baselined schema with version: 1
Migrating schema "public" to version "2 - lectures user index"
Successfully applied 1 migration, now at version v2
```
`flyway_schema_history`에 baseline(V1)과 V2가 기록되고 `idx_lectures_user`가 생성됨을 확인했습니다. **기존 데이터는 그대로 유지됩니다.**

**② 빈 DB (임시 `lecturemate_fresh` 생성)**
```
Migrating schema "public" to version "1 - init schema"
Migrating schema "public" to version "2 - lectures user index"
Successfully applied 2 migrations, now at version v2
```
테이블 7개(도메인 6 + 이력 1), HNSW 벡터 인덱스 2개가 생성됨을 확인한 뒤 임시 DB는 삭제했습니다.

**③ 테스트**
- Spring Boot 15개 통과 (테스트 DB도 baseline 후 V2 적용됨)
- FastAPI 5개 통과

## 사용법

스키마를 바꿀 때:
```bash
# 1. 새 마이그레이션 파일 작성 (기존 파일은 절대 수정하지 않는다)
server-core/src/main/resources/db/migration/V3__add_something.sql

# 2. 엔티티도 함께 수정한 뒤 기동하면 자동 적용된다
cd server-core && ./gradlew bootRun
```

주의할 점:
- 이미 적용된 마이그레이션 파일을 수정하면 체크섬이 달라져 기동이 실패합니다. 되돌리려면 새 마이그레이션을 추가하세요.
- 운영 배포 시에는 애플리케이션 기동과 동시에 마이그레이션이 실행됩니다. 인스턴스를 여러 대 띄우면 Flyway가 잠금으로 중복 실행을 막지만, 큰 변경은 배포 전에 따로 적용하는 편이 안전합니다.
