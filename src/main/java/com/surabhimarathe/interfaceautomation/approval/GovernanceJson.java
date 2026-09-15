package com.surabhimarathe.interfaceautomation.approval;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.cfg.*;
import tools.jackson.databind.type.LogicalType;
import tools.jackson.databind.json.JsonMapper;
import static com.surabhimarathe.interfaceautomation.approval.ApprovalException.*;

final class GovernanceJson {
    private final JsonMapper mapper = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .withCoercionConfig(LogicalType.Textual,c -> c.setCoercion(CoercionInputShape.Integer,CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float,CoercionAction.Fail).setCoercion(CoercionInputShape.Boolean,CoercionAction.Fail))
            .build();
    byte[] write(GovernanceEvent event) {
        try { return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(event); }
        catch (RuntimeException ex) { throw fail(Code.STORAGE_FAILURE); }
    }
    GovernanceEvent read(byte[] bytes) {
        try { return mapper.readValue(bytes,GovernanceEvent.class); }
        catch (RuntimeException ex) { throw fail(Code.CORRUPT_HISTORY); }
    }
}
