-- 004 초안(10 §2, 백엔드 소유). 정본은 supabase/migrations 이고 머지 뒤 사용자가 옮긴다. 테스트는 이 파일을 000b·001~003 뒤에 적용한다.
-- 업무 테이블(posts·post_rooms·votes·verdicts·verdict_texts·submissions·meme_images·privacy_invalidations)
-- + rooms.rule_version + 백엔드가 쓰는 ai 테이블 3개(privacy_epochs·verdict_commit_records·text_evidence_refs).
-- enum 규칙(10 §15.2 D-21): 프론트 값(소문자)은 Postgres native enum, 대문자 식별자(상태·소스·짤 태그)는 text + CHECK.
-- 10 §2 에 없는 것(post_rooms, votes, posts.vote_deadline_at, verdicts.target_intensities·default_intensity·last_failed_*,
-- verdict_texts.privacy_epoch_snapshot, submissions.intake_result, meme_images.image_url, privacy_invalidations)은 9/14 사용자 결정이고 AI 파트에 회신한다.
-- grants 는 10 §1 표. DELETE 는 누구에게도 주지 않는다(삭제는 소프트).

CREATE TYPE post_type AS ENUM ('spent', 'considering');
CREATE TYPE sentence AS ENUM ('probation', 'oneDay', 'life');

-- 규칙을 고칠 때마다 +1. rule_id 는 rules 배열 인덱스(0부터, 문자열). 9/14 사용자 결정(A안)
ALTER TABLE rooms ADD COLUMN rule_version integer NOT NULL DEFAULT 1;

CREATE TABLE meme_images (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tag text NOT NULL CHECK (tag IN ('GUILTY_HEAVY', 'GUILTY_LIGHT', 'NOT_GUILTY', 'APPROVED', 'REJECTED')),
    strategies text[] NOT NULL DEFAULT '{}',
    -- 감정 6종(10 §11)
    emotions text[] NOT NULL DEFAULT '{}'
        CHECK (emotions <@ ARRAY['DISAPPROVAL', 'ABSURD_SERIOUSNESS', 'SMUG', 'PITY', 'CELEBRATION', 'RESIGNATION']::text[]),
    keywords text[] NOT NULL DEFAULT '{}',
    image_url text NOT NULL,
    is_active boolean NOT NULL DEFAULT true
);

CREATE TABLE submissions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id uuid NOT NULL REFERENCES profiles (id),
    status text NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW', 'NEEDS_INPUT', 'COMPLETED', 'BLOCKED', 'EXPIRED')),
    payload_hash text NOT NULL,
    question_shown boolean NOT NULL DEFAULT false,
    final_check_count integer NOT NULL DEFAULT 0 CHECK (final_check_count >= 0),
    post_id uuid,
    expires_at timestamptz NOT NULL,
    intake_result jsonb,
    UNIQUE (actor_id, id)
);

CREATE TABLE posts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    author_id uuid NOT NULL REFERENCES profiles (id),
    post_type post_type NOT NULL,
    amount_krw integer NOT NULL CHECK (amount_krw > 0),
    category text NOT NULL CHECK (category IN ('식비', '배달', '카페/간식', '교통/택시', '쇼핑/패션', '뷰티',
                                               '취미/여가', '술/유흥', '구독', '생활', '기타')),
    item text NOT NULL CHECK (char_length(item) BETWEEN 1 AND 30),
    reason text CHECK (char_length(reason) <= 200),
    -- ai.jobs.aggregate_version > 0 이라 1부터
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    audience_version integer NOT NULL DEFAULT 1 CHECK (audience_version > 0),
    intake_status text NOT NULL CHECK (intake_status IN ('PASS', 'UNCLARIFIED')),
    intake_source text NOT NULL CHECK (intake_source IN ('AI', 'FALLBACK')),
    submission_id uuid UNIQUE REFERENCES submissions (id),
    -- 생성 시각 + 공유 방 vote_deadline_minutes 최소값(9/14 결정, 계산은 제출 흐름)
    vote_deadline_at timestamptz NOT NULL,
    deleted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE submissions ADD CONSTRAINT submissions_post_id_fkey FOREIGN KEY (post_id) REFERENCES posts (id);

-- 게시물이 공유된 방
CREATE TABLE post_rooms (
    post_id uuid NOT NULL REFERENCES posts (id),
    room_id uuid NOT NULL REFERENCES rooms (id),
    PRIMARY KEY (post_id, room_id)
);
CREATE INDEX post_rooms_room_idx ON post_rooms (room_id);

-- 게시물당 1인 1표, 수정 불가(9/14 결정). room_id 는 투표한 방
CREATE TABLE votes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id uuid NOT NULL REFERENCES posts (id),
    voter_id uuid NOT NULL REFERENCES profiles (id),
    room_id uuid NOT NULL REFERENCES rooms (id),
    verdict verdict NOT NULL,
    reason text NOT NULL CHECK (char_length(reason) BETWEEN 1 AND 500),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (post_id, voter_id)
);

CREATE TABLE verdicts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id uuid NOT NULL UNIQUE REFERENCES posts (id),
    -- ai.jobs.aggregate_version > 0, case-snapshot jury.verdict_version minimum 1
    verdict_version integer NOT NULL DEFAULT 1 CHECK (verdict_version > 0),
    jury_result verdict NOT NULL,
    -- {allowed_sentences[{code, rank}], fallback_sentence, reason_required, version}
    policy_snapshot jsonb NOT NULL,
    confirmed_at timestamptz NOT NULL,
    -- D-24: SENTENCE INSERT 전(PREPARE 대기)에는 NULL
    deadline_at timestamptz,
    sentence_status text NOT NULL DEFAULT 'PENDING' CHECK (sentence_status IN ('PENDING', 'FINAL')),
    sentence sentence,
    sentence_source text CHECK (sentence_source IN ('AI', 'RULE')),
    sentencing_reason text,
    reason_source text CHECK (reason_source IN ('AI', 'TEMPLATE')),
    text_status text NOT NULL DEFAULT 'PENDING'
        CHECK (text_status IN ('PENDING', 'GENERATING', 'TEMPLATE_READY', 'AI_READY')),
    text_version bigint NOT NULL DEFAULT 0,
    active_generation_id uuid,
    active_job_id uuid,
    retry_round integer NOT NULL DEFAULT 0 CHECK (retry_round >= 0),
    pending_retry_at timestamptz,
    applied_intensity spice_level,
    meme_image_id uuid REFERENCES meme_images (id),
    -- §4.1 jury 스냅샷 출처. 공유 방 강도 집합(문자열 배열)과 표 최다 방 강도(9/14 결정)
    target_intensities jsonb NOT NULL CHECK (jsonb_typeof(target_intensities) = 'array'),
    default_intensity spice_level NOT NULL,
    -- §4.6 generation-failed 재전송 판정
    last_failed_generation_id uuid,
    last_failed_code text
);
CREATE INDEX verdicts_pending_deadline_idx ON verdicts (deadline_at) WHERE sentence_status = 'PENDING';
CREATE INDEX verdicts_pending_retry_idx ON verdicts (pending_retry_at) WHERE pending_retry_at IS NOT NULL;

CREATE TABLE verdict_texts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    verdict_id uuid NOT NULL REFERENCES verdicts (id),
    intensity spice_level NOT NULL,
    headline text NOT NULL,
    -- 문장 배열 [{text, kind, evidence_labels}]
    statement jsonb NOT NULL CHECK (jsonb_typeof(statement) = 'array'),
    source text NOT NULL CHECK (source IN ('AI', 'TEMPLATE')),
    text_version bigint NOT NULL,
    dossier_id uuid,
    -- 저장 당시 [{scope_key, epoch}]. 조회 때 현재 epoch 와 비교(10 §8)
    privacy_epoch_snapshot jsonb,
    UNIQUE (verdict_id, intensity)
);

-- 10 §8 무효화 작업 기록. scope 하나당 1행, PENDING → DONE/FAILED
CREATE TABLE privacy_invalidations (
    id bigserial PRIMARY KEY,
    scope_key text NOT NULL,
    source_type text NOT NULL,
    source_id text NOT NULL,
    status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DONE', 'FAILED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    last_error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    processed_at timestamptz
);
CREATE INDEX privacy_invalidations_status_idx ON privacy_invalidations (status, created_at);

-- ai 테이블 3개(10 §2). 스키마 ai 는 001 이 만든다
-- 행이 없으면 epoch 0 으로 본다. 워커는 SELECT scope_key, epoch ... WHERE scope_key = ANY(:keys)
CREATE TABLE ai.privacy_epochs (
    scope_key text PRIMARY KEY,
    epoch bigint NOT NULL DEFAULT 0
);

-- finalize 1·3·12단계(10 §5)
CREATE TABLE ai.verdict_commit_records (
    generation_id uuid PRIMARY KEY,
    request_hash text NOT NULL,
    verdict_id uuid NOT NULL REFERENCES verdicts (id),
    text_version bigint NOT NULL,
    committed_at timestamptz NOT NULL DEFAULT now()
);

-- finalize 9단계, 무효화 역조회(10 §5·§8)
CREATE TABLE ai.text_evidence_refs (
    verdict_id uuid NOT NULL REFERENCES verdicts (id),
    text_version bigint NOT NULL,
    intensity text NOT NULL CHECK (intensity IN ('mild', 'spicy', 'hell')),
    field_path text NOT NULL,
    evidence_id uuid NOT NULL REFERENCES ai.evidence (id),
    PRIMARY KEY (verdict_id, text_version, intensity, field_path, evidence_id)
);
CREATE INDEX text_evidence_refs_evidence_idx ON ai.text_evidence_refs (evidence_id);

-- grants (10 §1). UPDATE 에는 WHERE 가 컬럼을 읽도록 SELECT 를 같이 준다
GRANT SELECT, INSERT, UPDATE ON meme_images, submissions, posts, post_rooms, votes, verdicts, verdict_texts,
    privacy_invalidations TO backend;
GRANT USAGE, SELECT ON SEQUENCE privacy_invalidations_id_seq TO backend;
GRANT SELECT, UPDATE (rule_version) ON rooms TO backend;
GRANT SELECT, INSERT, UPDATE ON ai.privacy_epochs, ai.verdict_commit_records, ai.text_evidence_refs TO backend;
GRANT SELECT ON ai.privacy_epochs TO ai_worker;
