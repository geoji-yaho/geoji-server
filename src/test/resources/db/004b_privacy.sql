-- 004 추가분(10 §8, 백엔드 소유). 정본은 supabase/migrations 이고 머지 뒤 사용자가 옮긴다.
-- backend role 에 DELETE 권한이 없어(004 grants) 방 공유 철회는 행을 지우지 않고 revoked_at 으로 표시한다.
-- 공유 방 조회는 revoked_at IS NULL 인 행만 본다. 10 §2 에 없는 컬럼이라 AI 파트에 회신한다.
ALTER TABLE post_rooms ADD COLUMN revoked_at timestamptz NULL;
