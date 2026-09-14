package com.ttegeoji.backend.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;

/**
 * DB 가 필요한 테스트의 부모. 테스트 JVM 하나에 Postgres 컨테이너 하나를 띄우고
 * classpath:db/*.sql 을 파일명 순으로 한 번 적용한다(000a → 000b → 001~003 → 004 → 004b_*).
 * 공유 Supabase 에는 붙지 않는다(testing 룰). 컨테이너는 JVM 종료 시 Testcontainers 가 치운다.
 */
@ActiveProfiles("test")
public abstract class PostgresContainerSupport {

    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES = startAndMigrate();

    private static PostgreSQLContainer startAndMigrate() {
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:16");
        container.start();
        applySqlFiles(container);
        return container;
    }

    private static void applySqlFiles(PostgreSQLContainer container) {
        Resource[] files;
        try {
            files = new PathMatchingResourcePatternResolver().getResources("classpath:db/*.sql");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Arrays.sort(files, Comparator.comparing(r -> Objects.requireNonNull(r.getFilename())));

        try (Connection connection = DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
             Statement statement = connection.createStatement()) {
            for (Resource file : files) {
                // 파일 전체를 한 번에 보낸다. pgjdbc 가 $$ 본문을 깨지 않고 문장을 나눈다
                try {
                    statement.execute(file.getContentAsString(StandardCharsets.UTF_8));
                } catch (SQLException e) {
                    throw new IllegalStateException("테스트 스키마 적용 실패: db/" + file.getFilename(), e);
                }
            }
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("테스트 DB 준비 실패", e);
        }
    }
}
