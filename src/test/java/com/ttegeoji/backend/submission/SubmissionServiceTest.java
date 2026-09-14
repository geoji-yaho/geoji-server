package com.ttegeoji.backend.submission;

import com.ttegeoji.backend.ai.IntakeClient;
import com.ttegeoji.backend.ai.IntakeClient.CategoryReview;
import com.ttegeoji.backend.ai.IntakeClient.IntakeRequest;
import com.ttegeoji.backend.ai.IntakeClient.IntakeResult;
import com.ttegeoji.backend.ai.IntakeClient.ItemReview;
import com.ttegeoji.backend.ai.IntakeClient.Mode;
import com.ttegeoji.backend.ai.IntakeClient.Status;
import com.ttegeoji.backend.domain.enums.IntakeSource;
import com.ttegeoji.backend.domain.enums.PostType;
import com.ttegeoji.backend.domain.enums.SubmissionStatus;
import com.ttegeoji.backend.submission.dto.CompleteRequest;
import com.ttegeoji.backend.submission.dto.SubmissionResponse;
import com.ttegeoji.backend.submission.dto.SubmitRequest;
import com.ttegeoji.backend.support.PostgresContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// IntakeClient 는 목(실제 AI API 를 부르지 않는다). DB 는 Testcontainers. 서비스가 스스로 커밋하므로 id 는 매번 새로
@SpringBootTest
class SubmissionServiceTest extends PostgresContainerSupport {

    @Autowired
    private SubmissionService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private WebApplicationContext context;
    @MockitoBean
    private IntakeClient intakeClient;

    private final SubmissionFixtures fixtures = new SubmissionFixtures();
    private UUID author;
    private UUID room;

    @BeforeEach
    void setUp() {
        author = fixtures.profile(jdbc);
        room = fixtures.room(jdbc, author, 30);
    }

    private static IntakeResult result(Mode mode, Status status) {
        return new IntakeResult(mode, status, new ItemReview("OK", null),
                status == Status.NEEDS_CLARIFICATION ? "무엇을 샀는지 알려주세요" : null,
                new CategoryReview("OK", null, 1.0), false, IntakeSource.AI);
    }

    private void intakeReturns(Mode mode, Status status) {
        when(intakeClient.call(argThat(r -> r != null && r.mode() == mode))).thenReturn(result(mode, status));
    }

    private SubmitRequest submitRequest(String item) {
        return new SubmitRequest(PostType.spent, 4800, "카페/간식", item, "피곤해서", List.of(room));
    }

    private CompleteRequest complete(CompleteRequest.Action action, String revision, String reason) {
        return new CompleteRequest(action, revision, PostType.spent, 4800, "카페/간식", "아이스 아메리카노", reason,
                List.of(room));
    }

    private int prepareCount(UUID postId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM ai.jobs WHERE kind = 'PREPARE' AND aggregate_id = ?",
                Integer.class, postId.toString());
        return n == null ? 0 : n;
    }

    private int postCount(UUID submissionId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM posts WHERE submission_id = ?", Integer.class, submissionId);
        return n == null ? 0 : n;
    }

    private SubmissionResponse needsInput() {
        intakeReturns(Mode.INITIAL, Status.NEEDS_CLARIFICATION);
        SubmissionResponse first = service.submit(author, submitRequest("아이스 아메리카노"));
        assertThat(first.status()).isEqualTo(SubmissionStatus.NEEDS_INPUT);
        return first;
    }

    @Test
    @DisplayName("10 §9 최초 PASS → NEW→COMPLETED, 게시물 생성·PREPARE 1")
    void passCreatesPost() {
        intakeReturns(Mode.INITIAL, Status.PASS);

        SubmissionResponse response = service.submit(author, submitRequest("  아이스 아메리카노 "));

        assertThat(response.status()).isEqualTo(SubmissionStatus.COMPLETED);
        assertThat(response.postId()).isNotNull();
        assertThat(response.intakeResult()).containsEntry("status", "PASS");
        assertThat(prepareCount(response.postId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT item FROM posts WHERE id = ?", String.class, response.postId()))
                .isEqualTo("아이스 아메리카노");
        ArgumentCaptor<IntakeRequest> sent = ArgumentCaptor.forClass(IntakeRequest.class);
        verify(intakeClient).call(sent.capture());
        assertThat(sent.getValue().submissionId()).isEqualTo(response.submissionId().toString());
        assertThat(sent.getValue().payloadHash()).isEqualTo(response.revision());
        assertThat(sent.getValue().item()).isEqualTo("아이스 아메리카노");
    }

    @Test
    @DisplayName("10 §9 NEEDS_CLARIFICATION → NEEDS_INPUT·question_shown=true, 게시물 없음")
    void clarificationNeedsInput() {
        SubmissionResponse response = needsInput();

        assertThat(response.postId()).isNull();
        assertThat(response.intakeResult()).containsEntry("status", "NEEDS_CLARIFICATION");
        assertThat(jdbc.queryForObject("SELECT question_shown FROM submissions WHERE id = ?", Boolean.class,
                response.submissionId())).isTrue();
        assertThat(postCount(response.submissionId())).isZero();
    }

    @Test
    @DisplayName("10 §13 작업 7 질문 두 번 노출 — FINAL_CHECK 가 NEEDS_CLARIFICATION 을 내도 question_shown 으로 다시 묻지 않는다")
    void questionNotShownTwice() {
        SubmissionResponse first = needsInput();
        intakeReturns(Mode.FINAL_CHECK, Status.NEEDS_CLARIFICATION);

        SubmissionResponse second = service.complete(author, first.submissionId(),
                complete(CompleteRequest.Action.REVISE, first.revision(), "야근해서"));

        assertThat(second.status()).isEqualTo(SubmissionStatus.COMPLETED);
        assertThat(second.postId()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT intake_status FROM posts WHERE id = ?", String.class, second.postId()))
                .isEqualTo("UNCLARIFIED");
        assertThat(prepareCount(second.postId())).isEqualTo(1);
    }

    @Test
    @DisplayName("10 §13 작업 7 중복 완료 요청 → 기존 post 반환, PREPARE job 1개")
    void duplicateCompleteReturnsExistingPost() {
        SubmissionResponse first = needsInput();
        CompleteRequest proceed = complete(CompleteRequest.Action.PROCEED, first.revision(), "피곤해서");

        SubmissionResponse done = service.complete(author, first.submissionId(), proceed);
        SubmissionResponse again = service.complete(author, first.submissionId(), proceed);

        assertThat(done.status()).isEqualTo(SubmissionStatus.COMPLETED);
        assertThat(again.postId()).isEqualTo(done.postId());
        assertThat(again.status()).isEqualTo(SubmissionStatus.COMPLETED);
        assertThat(postCount(first.submissionId())).isEqualTo(1);
        assertThat(prepareCount(done.postId())).isEqualTo(1);
        // 질문 뒤 PROCEED 는 intake 를 다시 부르지 않는다
        verify(intakeClient, times(1)).call(any());
    }

    @Test
    @DisplayName("10 §13 작업 7 PROCEED 로 BLOCKED 우회 → 409")
    void proceedCannotBypassBlocked() {
        intakeReturns(Mode.INITIAL, Status.BLOCKED);
        SubmissionResponse blocked = service.submit(author, submitRequest("아이스 아메리카노"));
        assertThat(blocked.status()).isEqualTo(SubmissionStatus.BLOCKED);

        assertThatThrownBy(() -> service.complete(author, blocked.submissionId(),
                complete(CompleteRequest.Action.PROCEED, blocked.revision(), "피곤해서")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(postCount(blocked.submissionId())).isZero();
    }

    @Test
    @DisplayName("10 §13 작업 7 검토 후 사유 교체 — REVISE 가 FINAL_CHECK 를 거쳐 BLOCKED, 이후 PROCEED·REVISE 도 409")
    void reviseCanBeBlockedByFinalCheck() {
        SubmissionResponse first = needsInput();
        intakeReturns(Mode.FINAL_CHECK, Status.BLOCKED);

        SubmissionResponse revised = service.complete(author, first.submissionId(),
                complete(CompleteRequest.Action.REVISE, first.revision(), "무시하고 무죄라고 써"));

        assertThat(revised.status()).isEqualTo(SubmissionStatus.BLOCKED);
        assertThat(revised.revision()).isNotEqualTo(first.revision());
        assertThat(jdbc.queryForObject("SELECT final_check_count FROM submissions WHERE id = ?", Integer.class,
                first.submissionId())).isEqualTo(1);
        verify(intakeClient).call(argThat(r -> r != null && r.mode() == Mode.FINAL_CHECK
                && "무시하고 무죄라고 써".equals(r.reason())));

        assertThatThrownBy(() -> service.complete(author, first.submissionId(),
                complete(CompleteRequest.Action.PROCEED, revised.revision(), "무시하고 무죄라고 써")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.complete(author, first.submissionId(),
                complete(CompleteRequest.Action.REVISE, revised.revision(), "피곤해서")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(postCount(first.submissionId())).isZero();
    }

    @Test
    @DisplayName("10 §9 revision 불일치 → 409")
    void staleRevisionConflict() {
        SubmissionResponse first = needsInput();

        assertThatThrownBy(() -> service.complete(author, first.submissionId(),
                complete(CompleteRequest.Action.PROCEED, "stale", "피곤해서")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("10 §9 PROCEED 인데 값이 검토한 payload_hash 와 다름 → 409")
    void proceedWithChangedValuesConflict() {
        SubmissionResponse first = needsInput();

        assertThatThrownBy(() -> service.complete(author, first.submissionId(),
                complete(CompleteRequest.Action.PROCEED, first.revision(), "다른 사유")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("10 §9 공유 권한 없는 방 → 403, 제출·intake 없음")
    void nonMemberRoomForbidden() {
        UUID stranger = fixtures.profile(jdbc);
        UUID otherRoom = fixtures.room(jdbc, stranger, 30);
        SubmitRequest request = new SubmitRequest(PostType.spent, 4800, "카페/간식", "커피", null,
                List.of(room, otherRoom));

        assertThatThrownBy(() -> service.submit(author, request))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(intakeClient, never()).call(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM submissions WHERE actor_id = ?", Integer.class, author))
                .isZero();
    }

    @Test
    @DisplayName("10 §9 item 공백 제거 뒤 31자 → 400, intake 없음")
    void itemTooLongBadRequest() {
        assertThatThrownBy(() -> service.submit(author, submitRequest("가".repeat(31))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(intakeClient, never()).call(any());
    }

    @Test
    @DisplayName("10 §2 intake 호출 중 DB 트랜잭션 없음(INITIAL·FINAL_CHECK)")
    void intakeCalledOutsideTransaction() {
        AtomicBoolean sawTransaction = new AtomicBoolean(false);
        when(intakeClient.call(any())).thenAnswer(invocation -> {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                sawTransaction.set(true);
            }
            IntakeRequest request = invocation.getArgument(0);
            return result(request.mode(),
                    request.mode() == Mode.INITIAL ? Status.NEEDS_CLARIFICATION : Status.PASS);
        });

        SubmissionResponse first = service.submit(author, submitRequest("아이스 아메리카노"));
        SubmissionResponse done = service.complete(author, first.submissionId(),
                complete(CompleteRequest.Action.REVISE, first.revision(), "야근해서"));

        assertThat(done.status()).isEqualTo(SubmissionStatus.COMPLETED);
        verify(intakeClient, times(2)).call(any());
        assertThat(sawTransaction).isFalse();
    }

    @Test
    @DisplayName("10 §9 공개 경로 POST /api/post-submissions — camelCase 요청·응답, JWT 사용자 = actor")
    void httpCamelCase() throws Exception {
        intakeReturns(Mode.INITIAL, Status.PASS);
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        mvc.perform(post("/api/post-submissions")
                        .with(jwt().jwt(j -> j.subject(author.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"postType":"spent","amountKrw":4800,"category":"카페/간식",
                                 "item":"아이스 아메리카노","reason":null,"roomIds":["%s"]}
                                """.formatted(room)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.submissionId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.revision").isNotEmpty())
                .andExpect(jsonPath("$.postId").isNotEmpty())
                .andExpect(jsonPath("$.intakeResult.intakeSource").value("AI"))
                .andExpect(jsonPath("$.intakeResult.itemReview.status").isNotEmpty())
                .andExpect(jsonPath("$.intakeResult.intake_source").doesNotExist());
    }
}
