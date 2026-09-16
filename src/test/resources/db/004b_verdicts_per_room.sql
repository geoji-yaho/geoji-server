-- 0017: 판결을 방마다 1건으로
--
-- 지출(게시물)은 작성자가 속한 모든 방에 올라가는데, 지금까지는 그 방들의 멤버를 하나의
-- 배심원단으로 합쳐 판결을 1건만 만들었다(verdicts.post_id UNIQUE). 서로 모르는 방 사람들이
-- 한 재판에 섞이고 A 방 2표 + B 방 1표 = 3표로 판결이 났다.
--
-- 이제 방마다 따로 재판한다. 게시물(지출 기록)만 공유되고 판결·투표·댓글은 방 안에서 끝난다.
-- 댓글은 이미 방별이다(post_comments.room_id).
--
-- 기존 판결은 room_id 를 NULL 로 남긴다 = "옛 합산 판결". 소급해서 다시 재판하지 않고,
-- 조회는 그 방 판결이 없으면 NULL 행으로 떨어뜨려 옛 게시물이 계속 보이게 한다.

alter table verdicts add column room_id uuid references rooms (id);

alter table verdicts drop constraint verdicts_post_id_key;
create unique index verdicts_post_room_key on verdicts (post_id, room_id);
-- 옛 합산 판결은 게시물당 하나만. 부분 유니크라 room_id 가 있는 행은 제약을 받지 않는다
create unique index verdicts_legacy_one_per_post on verdicts (post_id) where room_id is null;

-- 방마다 재판하므로 두 방에 다 있는 사람은 방마다 1표씩 낸다.
-- "게시물당 1인 1표"(10 §5, 9/14 결정)를 뒤집는 것이라 AI 파트에 회신한다
alter table votes drop constraint votes_post_id_voter_id_key;
alter table votes add constraint votes_post_voter_room_key unique (post_id, voter_id, room_id);

-- 방별 확정 스캔이 (게시물, 방) 쌍을 찾을 때 쓴다
create index verdicts_room_idx on verdicts (room_id) where room_id is not null;
