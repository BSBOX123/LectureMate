#!/bin/bash
# LectureMate DB 접속 (호스트에 psql 을 설치하지 않고 컨테이너의 psql 을 쓴다)
#   scripts/db.sh                       대화형 psql 접속
#   scripts/db.sh "select ..."          쿼리 한 번 실행
#   scripts/db.sh --test                테스트 DB(lecturemate_test) 로 접속

set -euo pipefail
export PATH="/opt/homebrew/bin:/usr/local/bin:$HOME/.orbstack/bin:$PATH"

DB=lecturemate
if [ "${1:-}" = "--test" ]; then
  DB=lecturemate_test
  shift
fi

if ! docker ps --format '{{.Names}}' | grep -q lecturemate-postgres; then
  echo "postgres 컨테이너가 꺼져 있습니다. 먼저 실행하세요: scripts/lecturemate.sh start" >&2
  exit 1
fi

if [ $# -gt 0 ]; then
  docker exec lecturemate-postgres psql -U postgres -d "$DB" -c "$*"
else
  # 한글 정렬과 넓은 출력을 보기 좋게
  docker exec -it lecturemate-postgres psql -U postgres -d "$DB" -P pager=off
fi
