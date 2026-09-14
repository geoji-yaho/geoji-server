-- 테스트 전용. 10 §1 role 3종을 없을 때만 만든다. 001~003 의 GRANT 가 이 role 을 전제로 한다(파일에 CREATE ROLE 이 없다).
-- 운영 Supabase 의 role 은 백엔드 몫이고 이 파일로 만들지 않는다. 테스트는 superuser 로 붙으므로 로그인 권한은 주지 않는다.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ai_api') THEN
        CREATE ROLE ai_api NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ai_worker') THEN
        CREATE ROLE ai_worker NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'backend') THEN
        CREATE ROLE backend NOLOGIN;
    END IF;
END
$$;
