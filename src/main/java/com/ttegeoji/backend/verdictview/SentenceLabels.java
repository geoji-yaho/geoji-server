package com.ttegeoji.backend.verdictview;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * 형량 라벨. TemplateCatalog 와 같은 classpath 파일(contracts/templates-v1.json)의 sentence_labels 를 읽는다.
 * TemplateCatalog 에 라벨 조회 메서드가 없고 verdict 패키지는 이 작업이 고치지 않는다.
 */
@Component
public class SentenceLabels {

    private static final String RESOURCE = "contracts/templates-v1.json";

    private final JsonNode labels;

    public SentenceLabels() {
        try (InputStream in = SentenceLabels.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("classpath 에 " + RESOURCE + " 이 없습니다");
            }
            this.labels = new ObjectMapper().readTree(in).get("sentence_labels");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 형량이 없거나 모르는 코드면 null */
    public String labelOf(String sentenceCode) {
        if (sentenceCode == null) {
            return null;
        }
        JsonNode label = labels.get(sentenceCode);
        return label == null || !label.isString() ? null : label.stringValue();
    }
}
