-- 강의(lecture) 중심 시절의 테이블을 정리한다.
--
-- V3 에서 과목 중심 구조로 이관할 때 이 테이블들을 지우지 않고 남겨 뒀다. 새 구조가 실제 사용에서
-- 검증된 뒤 지우기로 했고(Step 19), 이관 결과를 다음과 같이 확인한 뒤 지운다.
--
--   lecture_slides 169행 → material_pages 169행
--   내용(page_text, layout_data), 임베딩 유무, 임베딩 값(코사인 거리 1e-9 이내) 전부 일치
--   lecture_transcripts, slide_annotations 는 0행 (정렬·자동 필기를 돌린 적이 없다)
--
-- 코드에는 참조가 남아 있지 않다. slide_annotations 와 lecture_transcripts 를 쓰던 정렬·자동 필기
-- 기능은 Step 19 에서 제거했다.
--
-- 되살려야 하면 `~/lecturemate/backup/legacy-lecture-tables-20260925.sql` 을 psql 로 넣으면 된다.

-- slide_annotations 가 lecture_slides 를 참조하므로 먼저 지운다.
DROP TABLE IF EXISTS slide_annotations;
DROP TABLE IF EXISTS lecture_transcripts;
DROP TABLE IF EXISTS lecture_slides;
DROP TABLE IF EXISTS lectures;
