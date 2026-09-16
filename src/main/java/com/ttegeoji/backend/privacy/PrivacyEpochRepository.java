package com.ttegeoji.backend.privacy;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * ai.privacy_epochs(10 §2·§8). 행이 없으면 epoch 0 이다.
 * 잠금 순서의 첫 단계라 privacy scope 행을 verdict·job 행보다 먼저, key 오름차순으로 잠근다.
 */
@Repository
@RequiredArgsConstructor
public class PrivacyEpochRepository {

    // key 하나에 왕복 하나씩 쓰면 DB 가 다른 리전이라 scope 수만큼 지연이 붙는다(begin-generation 은 1초 예산).
    // 한 문장으로 넣되 ORDER BY 로 삽입 순서를 오름차순으로 묶어 교착 회피 성질은 그대로 둔다
    private static final String ENSURE_ROWS_SQL = """
            INSERT INTO ai.privacy_epochs (scope_key)
            SELECT k FROM unnest(?) AS t(k) ORDER BY k COLLATE "C"
            ON CONFLICT (scope_key) DO NOTHING""";
    // LockRows 가 Sort 위에 있어 정렬 순서대로 잠근다. COLLATE "C" 는 Java String 정렬과 같은 바이트 순서다
    private static final String LOCK_SQL =
            "SELECT scope_key, epoch FROM ai.privacy_epochs WHERE scope_key = ANY(?) ORDER BY scope_key COLLATE \"C\" FOR UPDATE";
    private static final String READ_SQL =
            "SELECT scope_key, epoch FROM ai.privacy_epochs WHERE scope_key = ANY(?)";
    // 행이 없으면 0 → 1
    private static final String BUMP_SQL = """
            INSERT INTO ai.privacy_epochs (scope_key, epoch) VALUES (?, 1)
            ON CONFLICT (scope_key) DO UPDATE SET epoch = ai.privacy_epochs.epoch + 1
            RETURNING epoch""";

    private final JdbcTemplate jdbcTemplate;

    /**
     * scope 행을 key 오름차순으로 잠그고 epoch 를 읽는다. 호출자 트랜잭션이 끝날 때까지 잠금이 유지된다.
     * 없는 행은 epoch 0 으로 먼저 만들어야 잠글 수 있다(0 행과 행 없음은 같은 뜻).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Long> lockAndRead(Collection<String> scopeKeys) {
        List<String> keys = sortedDistinct(scopeKeys);
        if (keys.isEmpty()) {
            return new TreeMap<>();
        }
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(ENSURE_ROWS_SQL);
            ps.setArray(1, connection.createArrayOf("text", keys.toArray()));
            return ps;
        });
        return query(LOCK_SQL, keys);
    }

    /** 잠그고 +1 한 뒤의 epoch. 무효화 트랜잭션에서 원본 비활성화보다 먼저 부른다(10 §8). */
    public long bump(String scopeKey) {
        Long epoch = jdbcTemplate.queryForObject(BUMP_SQL, Long.class, scopeKey);
        return epoch == null ? 0L : epoch;
    }

    /** 잠그지 않고 읽는다. 조회 화면의 epoch 비교용 */
    public Map<String, Long> read(Collection<String> scopeKeys) {
        List<String> keys = sortedDistinct(scopeKeys);
        if (keys.isEmpty()) {
            return new TreeMap<>();
        }
        return query(READ_SQL, keys);
    }

    private Map<String, Long> query(String sql, List<String> keys) {
        Map<String, Long> epochs = new TreeMap<>();
        for (String key : keys) {
            epochs.put(key, 0L);
        }
        jdbcTemplate.query(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql);
            ps.setArray(1, connection.createArrayOf("text", keys.toArray()));
            return ps;
        }, rs -> {
            epochs.put(rs.getString("scope_key"), rs.getLong("epoch"));
        });
        return epochs;
    }

    private static List<String> sortedDistinct(Collection<String> scopeKeys) {
        // 커밋 전 INSERT 는 같은 key 를 넣으려는 다른 트랜잭션을 기다리게 하므로 INSERT 도 오름차순이어야 교착이 없다.
        // scope key 는 ASCII(ScopeKeys)라 UTF-16 정렬과 COLLATE "C" 정렬이 같다
        return scopeKeys.stream().distinct().sorted().toList();
    }
}
