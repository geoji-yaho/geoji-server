package com.ttegeoji.backend.verdict;

import java.text.Normalizer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 기대 hash 는 AI 구현(geoji-agent src/geoji_ai/domain/draft_hash.py @ d3c7faa)으로 계산했다.
// cd "$GEOJIBANG_ROOT/geoji-agent" && uv run python -c "import json;from geoji_ai.domain.draft_hash import draft_hash;d=json.load(open('contracts/fixtures/writer-draft-taxi.json',encoding='utf-8'));s=json.load(open('contracts/fixtures/sentencing-taxi.json',encoding='utf-8'));print(draft_hash(d,s));print(draft_hash(d,None))"
class DraftHashTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    // 출처: geoji-agent contracts/fixtures/writer-draft-taxi.json (d3c7faa). src/test/resources 는 다른 담당 소유라 여기 둔다
    private static final String WRITER_DRAFT_TAXI = """
            {
              "schema_version": 1,
              "texts": [
                {
                  "intensity": "mild",
                  "headline": "택시 12,000원, 유죄",
                  "statement": [
                    {
                      "text": "지하철 기본요금 1,400원 기준으로 여덟 번 탈 돈입니다.",
                      "kind": "fact",
                      "evidence_labels": [
                        "F0"
                      ]
                    },
                    {
                      "text": "다음엔 알람을 조금만 일찍 맞춰 봐요.",
                      "kind": "opinion",
                      "evidence_labels": []
                    }
                  ],
                  "banter_strategy": "CHEAPER_ALTERNATIVE",
                  "selected_candidate_id": null,
                  "attack_angle": "CONVERSION",
                  "source": "AI"
                },
                {
                  "intensity": "spicy",
                  "headline": "또 늦잠, 또 택시",
                  "statement": [
                    {
                      "text": "택시 12,000원은 지하철 여덟 번 값입니다.",
                      "kind": "fact",
                      "evidence_labels": [
                        "F0"
                      ]
                    },
                    {
                      "text": "지난주에도 늦잠, 이번 주도 늦잠. 택시가 아니라 이불이 문제입니다.",
                      "kind": "claim",
                      "evidence_labels": [
                        "F2",
                        "F3"
                      ]
                    },
                    {
                      "text": "최근 7일 동안 택시 3회, 합계 31,000원.",
                      "kind": "fact",
                      "evidence_labels": [
                        "F1"
                      ]
                    }
                  ],
                  "banter_strategy": "REPEAT_OFFENSE",
                  "selected_candidate_id": "8d3f1a62-6c95-4b07-8e41-9a2d5f3b7c18",
                  "attack_angle": "CONVERSION",
                  "source": "AI"
                },
                {
                  "intensity": "hell",
                  "headline": "알람 시계 세 개 값",
                  "statement": [
                    {
                      "text": "12,000원이면 지하철 여덟 번입니다. 여덟 번.",
                      "kind": "fact",
                      "evidence_labels": [
                        "F0"
                      ]
                    },
                    {
                      "text": "최근 7일 동안 택시 3회, 31,000원을 태웠습니다.",
                      "kind": "fact",
                      "evidence_labels": [
                        "F1"
                      ]
                    },
                    {
                      "text": "31,000원이면 알람 시계 세 개입니다. 하나만 사세요.",
                      "kind": "claim",
                      "evidence_labels": [
                        "F1"
                      ]
                    }
                  ],
                  "banter_strategy": "CHEAPER_ALTERNATIVE",
                  "selected_candidate_id": "6a5d2f83-4b70-4e19-8c26-0f7a9b3e5d14",
                  "attack_angle": "CONVERSION",
                  "source": "AI"
                }
              ],
              "meme_tag": "GUILTY_LIGHT",
              "meme_hints": {
                "emotion": "DISAPPROVAL",
                "keywords": [
                  "택시",
                  "늦잠",
                  "알람"
                ]
              }
            }
            """;

    // 출처: geoji-agent contracts/fixtures/sentencing-taxi.json (d3c7faa)
    private static final String SENTENCING_TAXI = """
            {
              "schema_version": 1,
              "sentence": "oneDay",
              "sentencing_reason": "유죄율 75%로 실형 범위이나, 이번 달 예산 소진율이 아직 41%라 무기징역까지는 가지 않는다.",
              "reason_source": "AI",
              "evidence_labels": ["F1", "F5"],
              "aggravating": [],
              "mitigating": []
            }
            """;

    private static JsonNode read(String json) {
        return MAPPER.readTree(json);
    }

    @Test
    @DisplayName("10 §5 taxi 픽스처(draft + sentencing) → AI 구현과 같은 hash")
    void taxiWithSentencing() {
        assertThat(DraftHash.sha256Hex(read(WRITER_DRAFT_TAXI), read(SENTENCING_TAXI)))
                .isEqualTo("3142b8f108808c2a0ab98d9aba311d8cf7126c1b1b0f05da1a92638f1535014f");
    }

    @Test
    @DisplayName("10 §5 taxi draft + sentencing null → AI 구현과 같은 hash, Java null 과 NullNode 가 같다")
    void taxiWithoutSentencing() {
        String expected = "b208c0da715a73c78711ebd81b924df2c622acf2dfcbbed428ca3f88de1c8060";
        assertThat(DraftHash.sha256Hex(read(WRITER_DRAFT_TAXI), null)).isEqualTo(expected);
        assertThat(DraftHash.sha256Hex(read(WRITER_DRAFT_TAXI), NullNode.getInstance())).isEqualTo(expected);
    }

    @Test
    @DisplayName("10 §5 결과는 소문자 hex 64자")
    void lowercaseHex64() {
        assertThat(DraftHash.sha256Hex(read(WRITER_DRAFT_TAXI), read(SENTENCING_TAXI)))
                .matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("10 §5 NFD 한글(키·값) → NFC 와 같은 hash")
    void nfdEqualsNfc() {
        String key = "판결";
        String value = "택시 늦잠";
        ObjectNode nfc = F.objectNode().put(key, value);
        ObjectNode nfd = F.objectNode().put(
                Normalizer.normalize(key, Normalizer.Form.NFD),
                Normalizer.normalize(value, Normalizer.Form.NFD));
        assertThat(Normalizer.normalize(key, Normalizer.Form.NFD)).isNotEqualTo(key);

        assertThat(DraftHash.sha256Hex(nfd, null)).isEqualTo(DraftHash.sha256Hex(nfc, null));
    }

    @Test
    @DisplayName("10 §5 키 순서만 다름 → 같은 hash")
    void keyOrderIgnored() {
        JsonNode a = read("{\"b\":1,\"a\":{\"y\":true,\"x\":null}}");
        JsonNode b = read("{\"a\":{\"x\":null,\"y\":true},\"b\":1}");
        assertThat(DraftHash.sha256Hex(a, null)).isEqualTo(DraftHash.sha256Hex(b, null));
    }

    @Test
    @DisplayName("10 §5 배열 순서 다름 → 다른 hash")
    void arrayOrderMatters() {
        JsonNode a = read("{\"labels\":[\"F1\",\"F5\"]}");
        JsonNode b = read("{\"labels\":[\"F5\",\"F1\"]}");
        assertThat(DraftHash.sha256Hex(a, null)).isNotEqualTo(DraftHash.sha256Hex(b, null));
    }

    @Test
    @DisplayName("10 §5 경계값(제어문자·U+2028·실수 표기·보조 평면 키 정렬) → Python json.dumps 와 같은 canonical·hash")
    // 기대 hash 계산(geoji-agent d3c7faa):
    // cd "$GEOJIBANG_ROOT/geoji-agent" && uv run python -c "from geoji_ai.domain.draft_hash import draft_hash;d={'z':'a'+chr(1)+'b'+chr(10)+chr(9)+chr(34)+chr(92)+'/'+chr(127)+chr(0x2028),'a':[0.75,1e-05,1e16,1.0,-0.0,123456789012345678,3,True,None],'m':{chr(0x1F600):1,chr(0xFFFF):2}};print(draft_hash(d,None))"
    void edgeValuesMatchPython() {
        ArrayNode numbers = F.arrayNode()
                .add(0.75).add(1e-05).add(1e16).add(1.0).add(-0.0)
                .add(123456789012345678L).add(3).add(true).addNull();
        ObjectNode draft = F.objectNode();
        // 제어문자·U+2028·보조 평면은 소스에 원문자나 유니코드 이스케이프로 두지 않고 코드포인트로 만든다
        String ctl = Character.toString(0x0001);
        String del = Character.toString(0x007F);
        String lineSep = Character.toString(0x2028);
        String emoji = Character.toString(0x1F600);
        String nonChar = Character.toString(0xFFFF);
        // 입력 순서를 z·a·m 으로 넣어 정렬을 함께 본다
        draft.put("z", "a" + ctl + "b\n\t\"\\/" + del + lineSep);
        draft.set("a", numbers);
        draft.set("m", F.objectNode().put(emoji, 1).put(nonChar, 2));

        // U+FFFF 가 U+1F600 보다 앞(코드포인트 순). UTF-16 단위 비교면 반대가 된다
        String expectedCanonical = "{\"draft\":{"
                + "\"a\":[0.75,1e-05,1e+16,1.0,-0.0,123456789012345678,3,true,null],"
                + "\"m\":{\"" + nonChar + "\":2,\"" + emoji + "\":1},"
                + "\"z\":\"a\\" + "u0001b\\n\\t\\\"\\\\/" + del + lineSep + "\""
                + "},\"sentencing\":null}";

        assertThat(DraftHash.canonical(draft, null)).isEqualTo(expectedCanonical);
        assertThat(DraftHash.sha256Hex(draft, null))
                .isEqualTo("a139796784201604f85227f1c92e966c3764d46a07a81ede48721c2e42383d45");
    }

    @Test
    @DisplayName("10 §5 실수는 Python float repr 규칙(지수 -4 ≤ e < 16 고정소수, 나머지 e±NN)")
    void floatRepr() {
        assertThat(DraftHash.canonical(F.arrayNode().add(0.0001).add(1e15).add(123.456).add(1e100).add(2.5e-7), null))
                .isEqualTo("{\"draft\":[0.0001,1000000000000000.0,123.456,1e+100,2.5e-07],\"sentencing\":null}");
        assertThat(DraftHash.canonical(F.arrayNode().add(Double.MIN_VALUE).add(0.1 + 0.2), null))
                .isEqualTo("{\"draft\":[5e-324,0.30000000000000004],\"sentencing\":null}");
    }

    @Test
    @DisplayName("10 §5 짝 없는 서로게이트는 Python 처럼 hash 를 내지 않는다")
    void loneSurrogateRejected() {
        String lone = String.valueOf((char) 0xD800);
        assertThatThrownBy(() -> DraftHash.sha256Hex(F.objectNode().put("k", "a" + lone), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DraftHash.sha256Hex(F.objectNode().put(lone, 1), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("10 §5 FloatNode 는 JSON 텍스트 값으로 쓴다(0.1f → 0.1)")
    void floatNodeUsesDecimalText() {
        assertThat(DraftHash.canonical(F.arrayNode().add(0.1f), null))
                .isEqualTo("{\"draft\":[0.1],\"sentencing\":null}");
    }

    @Test
    @DisplayName("10 §5 NaN·Infinity 는 JSON 이 아니므로 거부")
    void nanRejected() {
        assertThatThrownBy(() -> DraftHash.sha256Hex(F.arrayNode().add(Double.NaN), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DraftHash.sha256Hex(F.arrayNode().add(Double.POSITIVE_INFINITY), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
