package com.ttegeoji.backend.submission;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

// 제출 테스트용 행. 커밋되므로 id 는 매번 새로 만든다
class SubmissionFixtures {

    UUID profile(JdbcTemplate jdbc) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO profiles (id, nickname, monthly_budget) VALUES (?, ?, ?)", id, "테스터", 500000);
        return id;
    }

    /** 방을 만들고 creator 를 멤버로 넣는다. */
    UUID room(JdbcTemplate jdbc, UUID creator, int voteDeadlineMinutes) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rooms (id, name, spice_level, vote_deadline_minutes, created_by)
                VALUES (?, '테스트방', 'mild', ?, ?)
                """, id, voteDeadlineMinutes, creator);
        jdbc.update("INSERT INTO room_members (room_id, user_id) VALUES (?, ?)", id, creator);
        return id;
    }

    // expires_at 은 004 초안이 NOT NULL 이라 테스트 행에만 값을 넣는다(만료 로직은 이번 범위 밖)
    UUID submission(JdbcTemplate jdbc, UUID actor) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO submissions (id, actor_id, payload_hash, expires_at)
                VALUES (?, ?, 'fixture', now() + interval '1 day')
                """, id, actor);
        return id;
    }
}
