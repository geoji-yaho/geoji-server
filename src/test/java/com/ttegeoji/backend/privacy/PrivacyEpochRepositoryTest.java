package com.ttegeoji.backend.privacy;

import com.ttegeoji.backend.support.PostgresContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

// 잠금 경합을 보려고 실제로 커밋한다. 테스트마다 무작위 scope key 를 써서 서로 섞이지 않는다
@SpringBootTest
class PrivacyEpochRepositoryTest extends PostgresContainerSupport {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    @Autowired
    private PrivacyEpochRepository repository;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("10 §2 행이 없으면 epoch 0")
    void missingRowIsZero() {
        String key = ScopeKeys.post(UUID.randomUUID());

        assertThat(repository.read(List.of(key))).containsExactly(Map.entry(key, 0L));
        Map<String, Long> locked = tx.execute(s -> repository.lockAndRead(List.of(key)));
        assertThat(locked).containsExactly(Map.entry(key, 0L));
    }

    @Test
    @DisplayName("10 §8 bump 뒤 epoch 1, 다시 bump 하면 2")
    void bumpIncrements() {
        String key = ScopeKeys.user(UUID.randomUUID());

        Long first = tx.execute(s -> repository.bump(key));
        assertThat(first).isEqualTo(1L);
        assertThat(repository.read(List.of(key))).containsEntry(key, 1L);
        Long second = tx.execute(s -> repository.bump(key));
        assertThat(second).isEqualTo(2L);
        Map<String, Long> locked = tx.execute(s -> repository.lockAndRead(List.of(key)));
        assertThat(locked).containsEntry(key, 2L);
    }

    @Test
    @DisplayName("10 §2 lockAndRead 는 요청 순서와 무관하게 key 오름차순으로 잠근다")
    void locksInAscendingKeyOrder() throws Exception {
        String prefix = "room:" + UUID.randomUUID() + ":";
        String a = prefix + "a";
        String b = prefix + "b";
        // heap 에 b 를 먼저 둔다. ORDER BY 가 없으면 seq scan 이 b 부터 잠가 이 테스트가 실패한다
        tx.executeWithoutResult(s -> {
            repository.bump(b);
            repository.bump(a);
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch holderLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            // T1 이 b 만 잡고 기다린다
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
                repository.lockAndRead(List.of(b));
                holderLocked.countDown();
                await(release);
            }));
            assertThat(holderLocked.await(10, TimeUnit.SECONDS)).isTrue();

            // T2 는 [b, a] 순서로 요청한다. 오름차순이면 a 를 먼저 잡고 b 에서 막힌다
            Future<Map<String, Long>> requester = pool.submit(() -> tx.execute(s -> {
                // PK 인덱스 순서에 기대지 않게 seq scan 을 강제한다
                jdbc.execute("SET LOCAL enable_indexscan = off");
                jdbc.execute("SET LOCAL enable_bitmapscan = off");
                return repository.lockAndRead(List.of(b, a));
            }));

            assertThat(waitUntilLockedByOther(a)).as("T2 가 a 를 먼저 잠가야 한다").isTrue();
            assertThat(requester.isDone()).as("T2 는 b 에서 기다린다").isFalse();

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            assertThat(requester.get(10, TimeUnit.SECONDS)).containsExactly(Map.entry(a, 1L), Map.entry(b, 1L));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("10 §2 두 트랜잭션이 반대 순서로 요청해도 교착 없이 끝난다")
    void oppositeOrderRequestsDoNotDeadlock() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 20; round++) {
                // 새 key 라 두 트랜잭션이 INSERT 부터 부딪친다
                String a = ScopeKeys.post(UUID.randomUUID());
                String b = ScopeKeys.room(UUID.randomUUID());
                CyclicBarrier start = new CyclicBarrier(2);
                Future<?> first = pool.submit(() -> lockHoldAndBump(start, List.of(a, b)));
                Future<?> second = pool.submit(() -> lockHoldAndBump(start, List.of(b, a)));

                first.get(10, TimeUnit.SECONDS);
                second.get(10, TimeUnit.SECONDS);
                assertThat(repository.read(List.of(a, b))).containsEntry(a, 2L).containsEntry(b, 2L);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("10 §2 backend 는 SELECT·INSERT·UPDATE, ai_worker 는 SELECT 만")
    void grants() {
        String key = ScopeKeys.post(UUID.randomUUID());

        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL ROLE backend");
            assertThat(repository.bump(key)).isEqualTo(1L);                          // INSERT
            assertThat(repository.bump(key)).isEqualTo(2L);                          // UPDATE
            assertThat(repository.lockAndRead(List.of(key))).containsEntry(key, 2L); // SELECT ... FOR UPDATE
        });

        tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL ROLE ai_worker");
            assertThat(repository.read(List.of(key))).containsEntry(key, 2L);
        });
        assertInsufficientPrivilege("INSERT INTO ai.privacy_epochs (scope_key) VALUES ('" + ScopeKeys.post(UUID.randomUUID()) + "')");
        assertInsufficientPrivilege("UPDATE ai.privacy_epochs SET epoch = epoch + 1 WHERE scope_key = '" + key + "'");

        assertThat(repository.read(List.of(key))).containsEntry(key, 2L);
    }

    private void assertInsufficientPrivilege(String sql) {
        Throwable thrown = catchThrowable(() -> tx.executeWithoutResult(s -> {
            jdbc.execute("SET LOCAL ROLE ai_worker");
            jdbc.execute(sql);
        }));
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(thrown);
        assertThat(cause).isInstanceOf(SQLException.class);
        assertThat(((SQLException) cause).getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    private void lockHoldAndBump(CyclicBarrier start, List<String> keys) {
        tx.executeWithoutResult(s -> {
            try {
                start.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            repository.lockAndRead(keys);
            sleep(20);
            keys.forEach(repository::bump);
        });
    }

    // 다른 트랜잭션이 key 를 잠갔으면 NOWAIT 가 55P03 으로 실패한다
    private boolean waitUntilLockedByOther(String key) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) {
            try {
                tx.executeWithoutResult(s -> jdbc.queryForList(
                        "SELECT epoch FROM ai.privacy_epochs WHERE scope_key = ? FOR UPDATE NOWAIT", key));
            } catch (DataAccessException e) {
                Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
                if (cause instanceof SQLException sql && LOCK_NOT_AVAILABLE.equals(sql.getSQLState())) {
                    return true;
                }
                throw e;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("release 대기 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
