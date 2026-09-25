-- 기존 데이터 1회성 정리: '데이터베이스'와 '데이터베이스 3장'은 같은 과목이다.
--
-- V3 는 lectures 1행 = courses 1행으로 일반 규칙에 따라 이관한다. 그 결과 같은 과목의
-- 자료 두 개가 서로 다른 과목으로 갈라졌다. 자료를 과목 사이로 옮기는 화면 기능이 없으므로
-- 여기서 합친다.
--
-- 해당 행이 없는 DB(신규 설치, 테스트 DB)에서는 아무 일도 일어나지 않는다.

DO $$
DECLARE
    target_id BIGINT;
    source_id BIGINT;
BEGIN
    SELECT id INTO target_id FROM courses WHERE title = '데이터베이스' ORDER BY id LIMIT 1;
    SELECT id INTO source_id FROM courses WHERE title = '데이터베이스 3장' ORDER BY id LIMIT 1;

    IF target_id IS NULL OR source_id IS NULL THEN
        RETURN;
    END IF;

    -- 소유자가 다르면 합치지 않는다 (다른 사용자의 자료를 끌어오는 사고 방지)
    IF (SELECT user_id FROM courses WHERE id = target_id)
       IS DISTINCT FROM (SELECT user_id FROM courses WHERE id = source_id) THEN
        RETURN;
    END IF;

    UPDATE course_materials   SET course_id = target_id WHERE course_id = source_id;
    UPDATE material_pages     SET course_id = target_id WHERE course_id = source_id;
    UPDATE course_recordings  SET course_id = target_id WHERE course_id = source_id;
    UPDATE recording_segments SET course_id = target_id WHERE course_id = source_id;

    -- 하위 행을 모두 옮겼으므로 CASCADE 로 지워지는 것은 없다
    DELETE FROM courses WHERE id = source_id;
END $$;
