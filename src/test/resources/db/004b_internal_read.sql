-- 004 추가분(feat-internal-read, 10 §4.1). 정본은 supabase/migrations 이고 머지 뒤 사용자가 옮긴다.
-- snapshot audience.public_share_enabled 의 출처. 10 §2 posts 에 없는 컬럼이라 AI 파트에 회신한다(9/15 사용자 결정).
-- 켜는 API 는 아직 없어 당분간 늘 false. Post 엔티티에는 매핑하지 않는다(validate 는 엔티티에 없는 컬럼을 허용한다).
ALTER TABLE posts ADD COLUMN public_share_enabled boolean NOT NULL DEFAULT false;
