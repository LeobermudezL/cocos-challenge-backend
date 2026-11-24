package com.cocos.challenge.order;

import jakarta.validation.constraints.NotNull;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.math.BigDecimal;

public record OrderSubmissionRequest(
        @NotNull @JsonDeserialize(using = JsonNumber.class) BigDecimal instrumentId,
        @NotNull String side,
        @NotNull String type,
        @JsonDeserialize(using = JsonNumber.class) BigDecimal quantity,
        @JsonDeserialize(using = JsonNumber.class) BigDecimal amount,
        @JsonDeserialize(using = JsonNumber.class) BigDecimal price) {

    static class JsonNumber extends ValueDeserializer<BigDecimal> {

        @Override
        public BigDecimal deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.currentToken().isNumeric()) {
                return context.reportInputMismatch(this, "Expected a JSON number");
            }
            return parser.getDecimalValue();
        }

        @Override
        public Object getNullValue(DeserializationContext context) {
            return context.reportInputMismatch(this, "Omit optional numeric fields instead of providing null");
        }

        @Override
        public Object getAbsentValue(DeserializationContext context) {
            return null;
        }
    }
}
