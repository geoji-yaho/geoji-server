package com.ttegeoji.backend.verdict;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplateCatalogTest {

    private final TemplateCatalog catalog = new TemplateCatalog();

    @Test
    @DisplayName("10 §10 version 은 templates-v1")
    void version() {
        assertThat(catalog.version()).isEqualTo("templates-v1");
    }

    @Test
    @DisplayName("10 §10 네 결과 headline 은 유죄·무죄·동의·기각")
    void headlines() {
        assertThat(catalog.render("guilty", 4, 3, "oneDay").headline()).isEqualTo("유죄");
        assertThat(catalog.render("notGuilty", 4, 1, null).headline()).isEqualTo("무죄");
        assertThat(catalog.render("agree", 4, 0, null).headline()).isEqualTo("동의");
        assertThat(catalog.render("disagree", 4, 0, null).headline()).isEqualTo("기각");
    }

    @Test
    @DisplayName("10 §10 유죄 카드 본문은 짧게, 형량은 sentencingReason으로 전달한다")
    void guiltySubstitution() {
        TemplateCatalog.Rendered rendered = catalog.render("guilty", 4, 3, "oneDay");

        assertThat(rendered.statement()).hasSize(1);
        assertThat(rendered.statement().get(0))
                .isEqualTo("배심원단이 이 지출을 유죄로 판단했습니다.");
        assertThat(rendered.sentencingReason()).isEqualTo("형량: 징역 1일 (내일 하루 무지출)");
    }

    @Test
    @DisplayName("10 §10 유죄 외 결과는 sentencingReason null")
    void nonGuiltyHasNoReason() {
        assertThat(catalog.render("notGuilty", 4, 1, null).sentencingReason()).isNull();
        assertThat(catalog.render("agree", 4, 0, null).sentencingReason()).isNull();
        assertThat(catalog.render("disagree", 4, 0, null).sentencingReason()).isNull();
        assertThat(catalog.render("notGuilty", 4, 1, null).statement())
                .containsExactly("배심원단이 이 지출을 무죄로 판단했습니다.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"guilty", "notGuilty", "agree", "disagree"})
    @DisplayName("10 §10 모든 폴백은 제목 20자·본문 1개 30자 이내다")
    void allFallbacksFitCard(String result) {
        TemplateCatalog.Rendered rendered = catalog.render(result, 1000, 999,
                result.equals("guilty") ? "life" : null);
        assertThat(rendered.headline().codePointCount(0, rendered.headline().length())).isBetween(1, 20);
        assertThat(rendered.statement()).hasSize(1).allSatisfy(line -> {
            assertThat(line).isNotBlank().doesNotContain("\n", "\r");
            assertThat(line.codePointCount(0, line.length())).isBetween(1, 30);
        });
    }

    @Test
    @DisplayName("10 §10 템플릿에 없는 결과(dismissed) → IllegalArgumentException")
    void dismissedRejected() {
        assertThatThrownBy(() -> catalog.render("dismissed", 1, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("10 §10 {sentence_label} 이 필요한데 형량이 없거나 모르는 코드 → IllegalArgumentException")
    void missingSentenceLabelRejected() {
        assertThatThrownBy(() -> catalog.render("guilty", 4, 3, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.render("guilty", 4, 3, "twoDays"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
