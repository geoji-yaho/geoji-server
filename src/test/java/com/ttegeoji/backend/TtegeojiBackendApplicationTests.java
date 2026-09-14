package com.ttegeoji.backend;

import com.ttegeoji.backend.support.PostgresContainerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class TtegeojiBackendApplicationTests extends PostgresContainerSupport {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	// ddl-auto: validate 라 엔티티와 000b 가 어긋나면 여기서 뜨지 않는다
	@Test
	void contextLoads() {
		String jobs = jdbcTemplate.queryForObject("SELECT to_regclass('ai.jobs')::text", String.class);
		assertThat(jobs).isEqualTo("ai.jobs");
	}

}
