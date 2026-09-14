package com.surabhimarathe.interfaceautomation.artifact;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;
import tools.jackson.databind.json.JsonMapper;

/** The supported persistence boundary; rejects unknown fields, coercion, and trailing content. */
public final class ArtifactJson {
    private static final int MAX_JSON_LENGTH = 128_000;
    private final ArtifactValidator validator = new ArtifactValidator();
    private final JsonMapper mapper = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .withCoercionConfig(LogicalType.Textual, c -> c
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .build();

    public CapabilityArtifact read(String json) {
        ArtifactValidator.require(json != null && json.length() <= MAX_JSON_LENGTH,
                ValidationCode.MALFORMED_ARTIFACT, "$");
        try {
            CapabilityArtifact artifact = mapper.readValue(json, CapabilityArtifact.class);
            validator.validate(artifact);
            return artifact;
        } catch (ArtifactValidationException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            // Do not expose Jackson exceptions, source excerpts, or rejected literals.
            throw ArtifactValidationException.at(ValidationCode.MALFORMED_ARTIFACT, "$");
        }
    }

    public String write(CapabilityArtifact artifact) {
        validator.validate(artifact);
        try {
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(artifact);
            ArtifactValidator.require(json.length() <= MAX_JSON_LENGTH, ValidationCode.MALFORMED_ARTIFACT, "$");
            return json;
        } catch (ArtifactValidationException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw ArtifactValidationException.at(ValidationCode.MALFORMED_ARTIFACT, "$");
        }
    }
}
