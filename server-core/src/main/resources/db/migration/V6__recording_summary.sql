-- 녹음 요약 (SPEC §2.1-11).
--
-- "교수님이 강조한 부분 찾아줘" 같은 질문은 채팅 RAG 로 답할 수 없다. 강조는 주제가 아니라서
-- 임베딩이 매칭할 대상이 없고, 1,373개 세그먼트에서 top_k=5 만 가져오면 71분 수업을 요약할 수
-- 없다. 검색 질문이 아니라 집계 질문이다.
--
-- 대신 전사 전체를 한 번에 LLM 에 넘긴다. 71분 녹음이 24,924자(약 8천 토큰)로 한 번에 들어간다.
-- 슬라이드마다 호출해 128번이 되던 자동 필기(Step 12, 제거됨)와 달리 **녹음당 1회**다.

ALTER TABLE course_recordings
    ADD COLUMN summary TEXT,
    ADD COLUMN summarized_at TIMESTAMP WITH TIME ZONE;
