package com.ttegeoji.backend.submission;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// 제출 흐름 조회(10 §9). 앞 작업의 repository 를 고치지 않고 여기 둔다(code-layout 룰)
@Component
@RequiredArgsConstructor
public class SubmissionQueries {

    private final NamedParameterJdbcTemplate jdbc;

    /** roomIds 중 userId 가 멤버인 방. */
    public Set<UUID> memberRoomIds(UUID userId, Collection<UUID> roomIds) {
        if (roomIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList(
                "SELECT room_id FROM room_members WHERE user_id = :userId AND room_id IN (:roomIds)",
                new MapSqlParameterSource().addValue("userId", userId).addValue("roomIds", roomIds),
                UUID.class));
    }

    /**
     * DB now() + 공유 방 vote_deadline_minutes 최소값(9/14 결정). now() 는 트랜잭션 시작 시각이라
     * 같은 트랜잭션에서 넣는 posts.created_at 과 기준이 같다. 방이 하나도 없으면 empty.
     */
    public Optional<OffsetDateTime> voteDeadlineAt(Collection<UUID> roomIds) {
        if (roomIds.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(jdbc.queryForObject(
                "SELECT now() + make_interval(mins => min(vote_deadline_minutes)) FROM rooms WHERE id IN (:roomIds)",
                new MapSqlParameterSource("roomIds", roomIds), OffsetDateTime.class));
    }
}
