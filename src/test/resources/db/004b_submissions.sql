-- 004 추가분, 정본은 supabase/migrations
-- 제출 만료(EXPIRED·만료 409)는 이번 범위 밖이라 expires_at 을 채우지 않는다(9/15 결정)
ALTER TABLE submissions ALTER COLUMN expires_at DROP NOT NULL;
