-- 강의 중심 → 과목 중심 구조 전환 (SPEC.md §3)
--
-- 기존: lectures 1개 = PDF 1개 + 녹음 1개 (전부 lecture_id 로 묶임)
-- 신규: courses 1개 = PDF N개(course_materials) + 녹음 N개(course_recordings)
--       RAG 검색은 과목 단위로 이뤄지므로 하위 테이블에 course_id 를 함께 둔다.
--
-- 기존 테이블(lectures, lecture_slides, lecture_transcripts, slide_annotations)은
-- 여기서 삭제하지 않는다. 새 구조가 실제 사용에서 검증된 뒤 별도 버전에서 정리한다.

-- 1. 과목 (폴더)
CREATE TABLE courses (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_courses_user ON courses(user_id);

-- 2. 과목에 속한 PDF 자료 (여러 개)
CREATE TABLE course_materials (
    id BIGSERIAL PRIMARY KEY,
    course_id BIGINT NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    pdf_url VARCHAR(500),
    status VARCHAR(50) NOT NULL DEFAULT 'PROCESSING', -- PROCESSING, READY, FAILED
    total_pages INT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_materials_course ON course_materials(course_id);

-- 3. 자료의 페이지별 텍스트 + 임베딩
--    layout_data(단어 bbox)는 현재 화면에서 쓰지 않지만, 파싱 시 거의 비용 없이 얻어지고
--    자동 필기(추후 과제)를 되살릴 때 재파싱을 피하려면 필요하므로 계속 저장한다.
CREATE TABLE material_pages (
    id BIGSERIAL PRIMARY KEY,
    material_id BIGINT NOT NULL REFERENCES course_materials(id) ON DELETE CASCADE,
    course_id BIGINT NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    page_number INT NOT NULL,
    page_text TEXT NOT NULL,
    layout_data JSONB NOT NULL,
    embedding vector(1024),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_material_pages_material ON material_pages(material_id, page_number);
CREATE INDEX idx_material_pages_course ON material_pages(course_id);
CREATE INDEX idx_material_pages_vector ON material_pages USING hnsw (embedding vector_cosine_ops);

-- 4. 과목에 속한 녹음 (여러 개, 자료와 독립)
CREATE TABLE course_recordings (
    id BIGSERIAL PRIMARY KEY,
    course_id BIGINT NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    audio_url VARCHAR(500),
    status VARCHAR(50) NOT NULL DEFAULT 'RECORDING', -- RECORDING, UPLOADED, ANALYZING, READY, FAILED
    duration_ms INT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_recordings_course ON course_recordings(course_id);

-- 5. 녹음 전사 세그먼트 + 임베딩
--    슬라이드 매칭(matched_slide_page)은 제거했다. 녹음이 특정 PDF 에 묶이지 않기 때문이다.
CREATE TABLE recording_segments (
    id BIGSERIAL PRIMARY KEY,
    recording_id BIGINT NOT NULL REFERENCES course_recordings(id) ON DELETE CASCADE,
    course_id BIGINT NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    start_time_ms INT NOT NULL,
    end_time_ms INT NOT NULL,
    speaker_text TEXT NOT NULL,
    embedding vector(1024),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_segments_recording ON recording_segments(recording_id, start_time_ms);
CREATE INDEX idx_segments_course ON recording_segments(course_id);
CREATE INDEX idx_segments_vector ON recording_segments USING hnsw (embedding vector_cosine_ops);

-- 6. 기존 데이터 이관
--    id 를 기존 lectures.id 그대로 쓴다. 저장된 파일명이 id 기반(/files/pdf/17.pdf)이라
--    이렇게 해야 파일을 옮기거나 이름을 바꾸지 않아도 된다.

INSERT INTO courses (id, user_id, title, created_at, updated_at)
SELECT id, user_id, title, created_at, updated_at FROM lectures;

INSERT INTO course_materials (id, course_id, title, pdf_url, status, total_pages, created_at, updated_at)
SELECT l.id,
       l.id,
       l.title,
       l.pdf_url,
       CASE WHEN EXISTS (SELECT 1 FROM lecture_slides s WHERE s.lecture_id = l.id)
            THEN 'READY' ELSE 'FAILED' END,
       (SELECT COUNT(*) FROM lecture_slides s WHERE s.lecture_id = l.id),
       l.created_at,
       l.updated_at
FROM lectures l
WHERE l.pdf_url IS NOT NULL;

INSERT INTO material_pages (material_id, course_id, page_number, page_text, layout_data, embedding, created_at)
SELECT s.lecture_id, s.lecture_id, s.page_number, s.slide_text, s.layout_data, s.embedding, s.created_at
FROM lecture_slides s
WHERE EXISTS (SELECT 1 FROM course_materials m WHERE m.id = s.lecture_id);

-- 녹음은 전사 결과가 있어야 READY. 없으면 아직 분석 전(UPLOADED)이다.
INSERT INTO course_recordings (id, course_id, title, audio_url, status, created_at, updated_at)
SELECT l.id,
       l.id,
       l.title,
       l.audio_url,
       CASE WHEN EXISTS (SELECT 1 FROM lecture_transcripts t WHERE t.lecture_id = l.id)
            THEN 'READY' ELSE 'UPLOADED' END,
       l.created_at,
       l.updated_at
FROM lectures l
WHERE l.audio_url IS NOT NULL;

INSERT INTO recording_segments (recording_id, course_id, start_time_ms, end_time_ms, speaker_text, embedding, created_at)
SELECT t.lecture_id, t.lecture_id, t.start_time_ms, t.end_time_ms, t.speaker_text, t.embedding, t.created_at
FROM lecture_transcripts t
WHERE EXISTS (SELECT 1 FROM course_recordings r WHERE r.id = t.lecture_id);

-- 명시적 id 삽입은 시퀀스를 올리지 않으므로 직접 맞춰 준다.
SELECT setval('courses_id_seq',           COALESCE((SELECT MAX(id) FROM courses), 0) + 1, false);
SELECT setval('course_materials_id_seq',  COALESCE((SELECT MAX(id) FROM course_materials), 0) + 1, false);
SELECT setval('course_recordings_id_seq', COALESCE((SELECT MAX(id) FROM course_recordings), 0) + 1, false);
