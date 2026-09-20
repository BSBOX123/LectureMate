-- 사용자별 강의 목록 조회(GET /api/v1/lectures)는 user_id 로 필터한다.
-- FK 컬럼에는 인덱스가 자동 생성되지 않아 전체 스캔이 발생하므로 추가한다.
CREATE INDEX IF NOT EXISTS idx_lectures_user ON lectures(user_id);
