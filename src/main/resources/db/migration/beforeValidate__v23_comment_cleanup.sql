-- V23의 실행 SQL은 그대로이며 설명 주석만 정리했습니다.
-- 알려진 기존 체크섬만 교체하며, 다른 변경은 기존 검증에서 계속 거절합니다.
DO $$
BEGIN
 IF to_regclass('flyway_schema_history') IS NOT NULL THEN
  UPDATE flyway_schema_history SET checksum=-1798846900
  WHERE version='23' AND script='V23__exercise_rest_preferences.sql'
    AND type='SQL' AND success=true AND checksum=-1769835489;
 END IF;
END $$;
