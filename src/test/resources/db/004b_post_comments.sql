-- 004 초안의 추가분(feat-post-comments, 10 §3·§4.1·§8). 정본은 supabase/migrations 이고 머지 뒤 사용자가 옮긴다.
-- 10 §2 에 없는 테이블이라 AI 파트에 회신한다. 기존 comments(expense 단위)와 별개다.
-- 파일명 순으로 004b_privacy(post_rooms.revoked_at)보다 먼저 적용되므로 여기서 revoked_at 을 참조하지 않는다.
-- version 은 ai.jobs.aggregate_version > 0·case-snapshot comment.version minimum 1 이라 1부터. 수정 기능이 없어 늘 1이다.
-- retained_at 은 RETAIN comment.approved job 을 넣은 시각. 판결 확정 전 댓글은 NULL 로 두고 스캔 스케줄러가 채운다.
-- backend 에 DELETE 권한이 없어 삭제는 deleted_at 표시다.
CREATE TABLE post_comments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id uuid NOT NULL REFERENCES posts (id),
    room_id uuid NOT NULL REFERENCES rooms (id),
    user_id uuid NOT NULL REFERENCES profiles (id),
    content text NOT NULL CHECK (char_length(content) BETWEEN 1 AND 200),
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    retained_at timestamptz,
    deleted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX post_comments_retain_pending_idx ON post_comments (post_id)
    WHERE retained_at IS NULL AND deleted_at IS NULL;

GRANT SELECT, INSERT, UPDATE ON post_comments TO backend;
