-- LectureMate AI 초기 스키마 (SPEC.md §3 Data Model & DB Schema)
-- docker-entrypoint-initdb.d 에 마운트되어 postgres 볼륨 최초 생성 시 실행됨

CREATE EXTENSION IF NOT EXISTS vector;

-- 0. 회원 정보 (Spring Security + JWT 인증 주체)
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL, -- BCrypt 해시
    name VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 1. 강의 정보 메타데이터
CREATE TABLE lectures (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    pdf_url VARCHAR(500),
    audio_url VARCHAR(500),
    status VARCHAR(50) DEFAULT 'INITIALIZED', -- INITIALIZED, PROCESSING, RECORDING, ANALYZING, READY, FAILED
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 2. PDF 슬라이드 페이지 데이터 (텍스트 + 단어 단위 Bounding Box 좌표 + 임베딩)
CREATE TABLE lecture_slides (
    id BIGSERIAL PRIMARY KEY,
    lecture_id BIGINT REFERENCES lectures(id) ON DELETE CASCADE,
    page_number INT NOT NULL,
    slide_text TEXT NOT NULL,
    layout_data JSONB NOT NULL, -- [{"word": "Dijkstra", "bbox": [100.2, 150.4, 180.0, 168.2]}, ...]
    embedding vector(1024),     -- BAAI/bge-m3 임베딩 차원
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_slides_lecture_page ON lecture_slides(lecture_id, page_number);
CREATE INDEX idx_slides_vector ON lecture_slides USING hnsw (embedding vector_cosine_ops);

-- 3. 오디오 전사 녹취록 (타임스탬프 + 매핑된 슬라이드 번호 + 임베딩)
CREATE TABLE lecture_transcripts (
    id BIGSERIAL PRIMARY KEY,
    lecture_id BIGINT REFERENCES lectures(id) ON DELETE CASCADE,
    start_time_ms INT NOT NULL,
    end_time_ms INT NOT NULL,
    speaker_text TEXT NOT NULL,
    matched_slide_page INT,     -- 단조 정렬 알고리즘으로 매핑된 슬라이드 번호
    embedding vector(1024),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_transcripts_lecture_slide ON lecture_transcripts(lecture_id, matched_slide_page);
CREATE INDEX idx_transcripts_vector ON lecture_transcripts USING hnsw (embedding vector_cosine_ops);

-- 4. 슬라이드별 최종 자동 필기 및 교수님 요약 레이어 (화면 렌더링용)
CREATE TABLE slide_annotations (
    id BIGSERIAL PRIMARY KEY,
    slide_id BIGINT REFERENCES lecture_slides(id) ON DELETE CASCADE,
    lecture_id BIGINT REFERENCES lectures(id) ON DELETE CASCADE,
    page_number INT NOT NULL,
    professor_summary TEXT NOT NULL,
    exam_hints TEXT,
    highlight_bboxes JSONB NOT NULL, -- [{"word": "...", "bbox": [x1, y1, x2, y2], "color": "#FFEB3B"}]
    confidence_score FLOAT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_annotations_lecture_page ON slide_annotations(lecture_id, page_number);
