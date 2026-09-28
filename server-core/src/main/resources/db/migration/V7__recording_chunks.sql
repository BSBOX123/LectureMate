-- 검색용 전사 덩어리 (SPEC §3).
--
-- Whisper 가 끊어 준 단위를 그대로 검색 대상으로 쓰던 것이 문제였다. 실측(9월 28일 수업 71분):
--
--   전체 1,373개 중 10자 미만 501개(36.5%), 20자 미만 786개(57%), 평균 21자
--   "박준오" "유호찬" "이다은" ... 출석 부르는 소리가 각각 한 행에 벡터까지 하나씩
--
-- 20자 조각에는 임베딩할 의미가 거의 없어서 "교수님이 강조한 부분" 질문에 "수요일 강의가
-- 인정이 됩니다" 같은 무관한 조각이 걸렸다. 검색이 나쁜 게 아니라 검색 대상이 부서져 있었다.
--
-- 이웃 세그먼트를 묶어 자료 페이지와 비슷한 크기(평균 310자)로 만든다. 실측상 935개 → 72개.
-- **원본 세그먼트는 그대로 남긴다** — 요약이 정밀한 타임스탬프를 쓰고, 파라미터를 바꿔 다시
-- 묶으려면 원본이 있어야 한다 (전사에 42분이 든다).

CREATE TABLE recording_chunks (
    id BIGSERIAL PRIMARY KEY,
    recording_id BIGINT NOT NULL REFERENCES course_recordings(id) ON DELETE CASCADE,
    course_id BIGINT NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    start_time_ms INT NOT NULL,
    end_time_ms INT NOT NULL,
    chunk_text TEXT NOT NULL,
    embedding vector(1024),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_chunks_recording ON recording_chunks(recording_id, start_time_ms);
CREATE INDEX idx_chunks_course ON recording_chunks(course_id);
CREATE INDEX idx_chunks_vector ON recording_chunks USING hnsw (embedding vector_cosine_ops);

-- 검색은 이제 덩어리로 한다. 세그먼트의 임베딩은 쓰이지 않으므로 지운다
-- (1,373행 × 4KB 데이터 + HNSW 인덱스 8.8MB 회수). 세그먼트 텍스트와 타임스탬프는 남는다.
DROP INDEX IF EXISTS idx_segments_vector;
ALTER TABLE recording_segments DROP COLUMN IF EXISTS embedding;
