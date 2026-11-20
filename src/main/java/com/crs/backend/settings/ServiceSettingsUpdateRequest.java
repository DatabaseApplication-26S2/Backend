package com.crs.backend.settings;

import com.fasterxml.jackson.annotation.JsonCreator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.node.DecimalNode;
import tools.jackson.databind.node.DoubleNode;
import tools.jackson.databind.node.IntNode;

/**
 * openapi.yaml ServiceSettingsUpdateRequest. concurrencyLimit만 받는다(다른 필드는 400 E09, BE-01 설정).
 *
 * concurrencyLimit은 openapi.yaml에서 type: integer(1~1000)이고 ADM-02-01은 "N 범위·정수 조건 위반 → 400 E09"다.
 * Integer로 받으면 Jackson 기본 강제 변환이 "5"를 5로, 1.5를 1로 바꿔 저장해 버리므로
 * 받은 JSON 값을 노드 그대로 두고 타입·정수·범위는 ServiceSettingsService가 판정한다.
 *   - 빠짐, JSON null      → null
 *   - 문자열·불리언·객체·배열 → 그 노드 그대로
 *   - 소수 표기 숫자        → double로 반올림하지 않은 BigDecimal(20.0은 정수, 20.0000000000000001은 정수 아님)
 */
public record ServiceSettingsUpdateRequest(
        @JsonDeserialize(using = ExactNumberDeserializer.class) JsonNode concurrencyLimit) {

    /**
     * 서비스를 직접 부르는 쪽(테스트 포함)이 쓰는 생성자. JSON 본문에는 쓰지 않는다.
     * 공개 int 생성자는 Jackson이 숫자 하나뿐인 본문(예: 5)을 받는 생성자로 잡으므로 DISABLED로 막는다.
     */
    @JsonCreator(mode = JsonCreator.Mode.DISABLED)
    public ServiceSettingsUpdateRequest(int concurrencyLimit) {
        this(IntNode.valueOf(concurrencyLimit));
    }

    /** 소수 표기 숫자만 정확한 BigDecimal 노드로 읽고, 나머지 값은 받은 JSON 노드 그대로 둔다 */
    static final class ExactNumberDeserializer extends ValueDeserializer<JsonNode> {

        @Override
        public JsonNode deserialize(JsonParser p, DeserializationContext ctxt) {
            if (p.currentToken() != JsonToken.VALUE_NUMBER_FLOAT) {
                return ctxt.readTree(p);
            }
            try {
                return DecimalNode.valueOf(p.getDecimalValue());
            } catch (NumberFormatException e) {
                // 지수가 int 범위를 넘어 BigDecimal로 못 담는 수(1e99999999999 등)는 1000보다 크거나 1보다 작다.
                // double(무한대 또는 0)로 두면 서비스가 같은 범위 위반(/concurrencyLimit)으로 거절한다
                return DoubleNode.valueOf(p.getDoubleValue());
            }
        }
    }
}
