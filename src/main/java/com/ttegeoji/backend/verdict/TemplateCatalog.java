package com.ttegeoji.backend.verdict;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 10 §10 폴백 템플릿. AI 워커가 판결문을 못 만들었을 때 백엔드가 같은 문구를 쓰도록
 * AI 저장소 파일을 바이트 그대로 복사해 버전을 고정한다.
 * 출처: geoji-agent contracts/fixtures/templates-v1.json @ 48c06f834201479fb7403764ca04e6b7508dfe8d
 * 형량은 아직 Sentence enum 이 없어 문자열 코드(probation|oneDay|life)로 받는다.
 */
@Component
public class TemplateCatalog {

    static final String RESOURCE = "contracts/templates-v1.json";
    private static final String SENTENCE_LABEL = "{sentence_label}";

    public record Rendered(String headline, List<String> statement, String sentencingReason) {
        public Rendered {
            statement = List.copyOf(statement);
        }
    }

    private final JsonNode root;

    public TemplateCatalog() {
        try (InputStream in = TemplateCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("classpath 에 " + RESOURCE + " 이 없습니다");
            }
            this.root = new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String version() {
        return root.get("version").stringValue();
    }

    public Rendered render(String result, int juryCount, int guiltyCount, String sentenceCodeOrNull) {
        JsonNode template = result == null ? null : root.get("results").get(result);
        if (template == null) {
            throw new IllegalArgumentException("템플릿에 없는 결과: " + result);
        }

        List<String> statement = new ArrayList<>();
        for (JsonNode line : template.get("statement").values()) {
            statement.add(fill(line.stringValue(), juryCount, guiltyCount, sentenceCodeOrNull));
        }
        JsonNode reason = template.get("sentencing_reason_template");
        String sentencingReason = reason == null || reason.isNull()
                ? null
                : fill(reason.stringValue(), juryCount, guiltyCount, sentenceCodeOrNull);

        return new Rendered(
                fill(template.get("headline").stringValue(), juryCount, guiltyCount, sentenceCodeOrNull),
                statement,
                sentencingReason);
    }

    private String fill(String text, int juryCount, int guiltyCount, String sentenceCode) {
        String filled = text
                .replace("{n}", Integer.toString(juryCount))
                .replace("{m}", Integer.toString(guiltyCount));
        if (!filled.contains(SENTENCE_LABEL)) {
            return filled;
        }
        JsonNode label = sentenceCode == null ? null : root.get("sentence_labels").get(sentenceCode);
        if (label == null || !label.isString()) {
            throw new IllegalArgumentException("형량 라벨이 필요한 템플릿인데 형량이 없거나 모르는 코드: " + sentenceCode);
        }
        return filled.replace(SENTENCE_LABEL, label.stringValue());
    }
}
