-- 10 §16.5 관리자 짤 업로드. 같은 파일을 두 번 올려도 후보가 늘지 않게 자산 키를 유니크로 둔다.
-- 운영에는 supabase/migrations/0016_seed_meme_images.sql 이 같은 컬럼을 이미 만들었다.
-- 시드로 넣은 행은 자산 이름(예: disappointed), 업로드한 행은 바이트의 SHA-256 이 들어간다.
alter table meme_images add column if not exists asset_key text unique;
