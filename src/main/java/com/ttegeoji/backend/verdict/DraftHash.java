package com.ttegeoji.backend.verdict;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * 10 §5 draft_hash. AI 구현 geoji-agent src/geoji_ai/domain/draft_hash.py 의
 * json.dumps(sort_keys=True, separators=(",",":"), ensure_ascii=False) 와 바이트가 같아야
 * finalize 가 워커의 hash 를 재계산으로 확인할 수 있다. Jackson 직렬화는 키 정렬·실수 표기·
 * 이스케이프가 Python 과 달라 직접 쓴다.
 */
public final class DraftHash {

    private DraftHash() {
    }

    public static String sha256Hex(JsonNode draft, JsonNode sentencingOrNull) {
        byte[] bytes = canonical(draft, sentencingOrNull).getBytes(StandardCharsets.UTF_8);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", e);
        }
    }

    static String canonical(JsonNode draft, JsonNode sentencingOrNull) {
        StringBuilder out = new StringBuilder();
        // 최상위 키 "draft" < "sentencing" 은 이미 정렬 순서다
        out.append("{\"draft\":");
        write(draft, out);
        out.append(",\"sentencing\":");
        write(sentencingOrNull, out);
        out.append('}');
        return out.toString();
    }

    private static void write(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            out.append("null");
        } else if (node.isObject()) {
            writeObject(node, out);
        } else if (node.isArray()) {
            out.append('[');
            boolean first = true;
            for (JsonNode item : node.values()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(item, out);
            }
            out.append(']');
        } else if (node.isString()) {
            writeString(nfc(node.stringValue()), out);
        } else if (node.isBoolean()) {
            out.append(node.booleanValue() ? "true" : "false");
        } else if (node.isIntegralNumber()) {
            out.append(node.bigIntegerValue());
        } else if (node.isFloat()) {
            // 코드에서 만든 FloatNode 는 넓히면 0.10000000149011612 가 된다. JSON 텍스트로 쓴 값(0.1)으로 본다
            out.append(pythonFloatRepr(Double.parseDouble(Float.toString(node.floatValue()))));
        } else if (node.isNumber()) {
            // Python json 파서는 소수·지수 표기를 float 로 읽으므로 BigDecimal 노드도 double 로 본다
            out.append(pythonFloatRepr(node.doubleValue()));
        } else {
            throw new IllegalArgumentException("JSON 으로 직렬화할 수 없는 노드: " + node.getNodeType());
        }
    }

    private static void writeObject(JsonNode node, StringBuilder out) {
        // NFC 후 같은 키가 되면 Python dict 처럼 뒤에 온 값이 이긴다
        Map<String, JsonNode> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            normalized.put(nfc(entry.getKey()), entry.getValue());
        }
        List<String> keys = new ArrayList<>(normalized.keySet());
        // String.compareTo 는 UTF-16 단위라 보조 평면 문자에서 Python(코드포인트 순)과 달라진다
        keys.sort(DraftHash::compareCodePoints);

        out.append('{');
        boolean first = true;
        for (String key : keys) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeString(key, out);
            out.append(':');
            write(normalized.get(key), out);
        }
        out.append('}');
    }

    private static int compareCodePoints(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private static String nfc(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC);
    }

    // Python ensure_ascii=False: " \ 와 U+0000~U+001F 만 이스케이프한다
    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (Character.isHighSurrogate(c) && i + 1 < value.length()
                            && Character.isLowSurrogate(value.charAt(i + 1))) {
                        out.append(c).append(value.charAt(++i));
                    } else if (Character.isSurrogate(c)) {
                        // Python 은 UTF-8 인코딩에서 실패한다. getBytes 가 ? 로 바꿔 hash 를 내지 않게 막는다
                        throw new IllegalArgumentException("짝 없는 서로게이트는 UTF-8 로 인코딩할 수 없습니다");
                    } else if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /**
     * Python float.__repr__. 왕복하는 가장 짧은 10진 자릿수(같은 길이면 실제 값에 가까운 쪽)를 고르고,
     * 10진 지수 e 가 -4 ≤ e < 16 이면 고정소수, 아니면 1e-05 형식으로 쓴다.
     * Double.toString 은 한 자리로 충분할 때도 두 자리를 내는 경우(4.9E-324)가 있어 직접 찾는다.
     */
    static String pythonFloatRepr(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("NaN·Infinity 는 JSON 이 아닙니다");
        }
        boolean negative = Double.doubleToRawLongBits(value) < 0;
        String sign = negative ? "-" : "";
        if (value == 0.0) {
            return sign + "0.0";
        }

        BigDecimal digits = shortestRoundTrip(Math.abs(value)).stripTrailingZeros();
        String unscaled = digits.unscaledValue().toString();
        int exponent = unscaled.length() - 1 - digits.scale();

        if (exponent >= -4 && exponent < 16) {
            if (exponent >= 0) {
                int intLength = exponent + 1;
                if (unscaled.length() <= intLength) {
                    return sign + unscaled + "0".repeat(intLength - unscaled.length()) + ".0";
                }
                return sign + unscaled.substring(0, intLength) + "." + unscaled.substring(intLength);
            }
            return sign + "0." + "0".repeat(-exponent - 1) + unscaled;
        }

        String mantissa = unscaled.length() == 1
                ? unscaled
                : unscaled.charAt(0) + "." + unscaled.substring(1);
        String expSign = exponent < 0 ? "-" : "+";
        int absExp = Math.abs(exponent);
        return sign + mantissa + "e" + expSign + (absExp < 10 ? "0" : "") + absExp;
    }

    private static BigDecimal shortestRoundTrip(double positive) {
        BigDecimal exact = new BigDecimal(positive);
        for (int precision = 1; precision <= 17; precision++) {
            BigDecimal down = round(exact, precision, RoundingMode.FLOOR);
            BigDecimal up = round(exact, precision, RoundingMode.CEILING);
            boolean downOk = down.doubleValue() == positive;
            boolean upOk = up.doubleValue() == positive;
            if (downOk && upOk) {
                int cmp = exact.subtract(down).compareTo(up.subtract(exact));
                return cmp <= 0 ? down : up;
            }
            if (downOk) {
                return down;
            }
            if (upOk) {
                return up;
            }
        }
        // 17 자리면 모든 double 이 왕복한다
        return round(exact, 17, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal round(BigDecimal value, int precision, RoundingMode mode) {
        return value.round(new java.math.MathContext(precision, mode));
    }
}
