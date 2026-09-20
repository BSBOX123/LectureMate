-- 테스트 전용 데이터베이스. 통합 테스트가 개발용 데이터를 지우지 않도록 분리한다.
-- 01-init.sql 과 같은 스키마를 그대로 적용한다.
CREATE DATABASE lecturemate_test;
\connect lecturemate_test
\i /docker-entrypoint-initdb.d/01-init.sql
