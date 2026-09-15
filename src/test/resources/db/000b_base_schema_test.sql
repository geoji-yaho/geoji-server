-- 테스트 전용. 정본은 supabase/migrations(이 저장소에 없다, 10 §15.1). 운영에 적용하지 않는다.
-- 기존 엔티티(src/main/java/com/ttegeoji/backend/domain) 11개 테이블을 ddl-auto: validate 가 통과하게 만든다.
-- 생성 방법(9/14): @SpringBootTest 에 아래 속성을 주고 한 번 띄워 Hibernate 스키마 생성 스크립트를 뽑았다.
--   spring.jpa.hibernate.ddl-auto=none
--   spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=create
--   spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target=<파일>
--   spring.jpa.properties.hibernate.hbm2ddl.delimiter=;
-- 뽑은 뒤 손으로 고친 것:
--   * enum 타입 이름을 Java 클래스명(ChallengeStatus) 대신 엔티티 columnDefinition(challenge_status)으로, 라벨 순서는 Java 선언 순서로. 암시적 cast 는 뺐다
--   * varchar(255) → text, numeric(38,2) → numeric (길이 제한이 테스트 데이터를 막지 않게)
--   * @Generated 컬럼 기본값: created_at·updated_at·joined_at = now(), rooms.invite_code = 무작위 hex, id = gen_random_uuid()
--   * updated_at 트리거 set_updated_at(엔티티 주석이 가리키는 DB 트리거)
-- 엔티티를 바꾸면 이 파일도 같이 고친다. FK·인덱스·RLS 는 운영 스키마에만 있고 여기 없다.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TYPE challenge_status AS ENUM ('pending', 'accepted', 'success', 'failed', 'expired');
CREATE TYPE expense_source AS ENUM ('quick_tap', 'purchase_check');
CREATE TYPE verdict AS ENUM ('guilty', 'notGuilty', 'agree', 'disagree', 'dismissed');
CREATE TYPE patrol_response AS ENUM ('spent', 'no_spend', 'ignored');
CREATE TYPE spice_level AS ENUM ('mild', 'spicy', 'hell');
CREATE TYPE award_type AS ENUM ('fixed_king', 'fixed_spender', 'invented');

CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TABLE profiles (
    id uuid NOT NULL,
    nickname text NOT NULL,
    avatar_url text,
    monthly_budget integer NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

CREATE TABLE rooms (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    name text NOT NULL,
    spice_level spice_level NOT NULL,
    vote_deadline_minutes integer NOT NULL,
    rules text[] NOT NULL DEFAULT '{}',
    invite_code text NOT NULL UNIQUE DEFAULT encode(gen_random_bytes(4), 'hex'),
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    PRIMARY KEY (id)
);

CREATE TABLE room_members (
    room_id uuid NOT NULL,
    user_id uuid NOT NULL,
    debt_score numeric,
    joined_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (room_id, user_id)
);

CREATE TABLE expenses (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL,
    amount integer NOT NULL,
    category text,
    memo text,
    source expense_source NOT NULL,
    spent_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

CREATE TABLE expense_trials (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    room_id uuid NOT NULL,
    expense_id uuid NOT NULL,
    voting_deadline timestamptz NOT NULL,
    verdict verdict,
    verdict_text text,
    sentence_days integer,
    sentence_started_at timestamptz,
    sentence_ended_at timestamptz,
    judged_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

CREATE TABLE expense_votes (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    trial_id uuid NOT NULL,
    voter_user_id uuid NOT NULL,
    verdict verdict NOT NULL,
    reason text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

CREATE TABLE comments (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    expense_id uuid NOT NULL,
    room_id uuid NOT NULL,
    user_id uuid NOT NULL,
    content text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

CREATE TABLE weekly_awards (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    room_id uuid NOT NULL,
    week_start date NOT NULL,
    week_end date NOT NULL,
    award_type award_type NOT NULL,
    title text NOT NULL,
    winner_user_id uuid,
    description text,
    stats_snapshot jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

CREATE TABLE challenges (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    room_id uuid NOT NULL,
    user_id uuid NOT NULL,
    week_start date NOT NULL,
    items jsonb NOT NULL,
    narrative text,
    target_amount integer NOT NULL,
    status challenge_status NOT NULL,
    accepted_at timestamptz,
    resolved_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

CREATE TABLE patrol_notifications (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    room_id uuid NOT NULL,
    user_id uuid NOT NULL,
    scheduled_at timestamptz NOT NULL,
    sent_at timestamptz,
    risk_reason text,
    response patrol_response,
    responded_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

CREATE TABLE daily_logs (
    id uuid NOT NULL DEFAULT gen_random_uuid(),
    room_id uuid NOT NULL,
    log_date date NOT NULL,
    summary text,
    collapse_time time(0),
    mvp_user_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id)
);

CREATE TRIGGER profiles_set_updated_at BEFORE UPDATE ON profiles
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER rooms_set_updated_at BEFORE UPDATE ON rooms
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
