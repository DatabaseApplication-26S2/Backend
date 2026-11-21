package com.crs.backend.auth;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.*;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

/** Shared OpenAPI 3.1 response validator: operation/status/content/headers + full 2020-12 schema. */
public final class OpenApiResponses {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNode SPEC;
    static {
        try (var input = OpenApiResponses.class.getResourceAsStream("/contract/openapi.yaml")) {
            SPEC = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()).readTree(input);
        } catch (Exception ex) { throw new ExceptionInInitializerError(ex); }
    }
    private static JsonNode resolve(JsonNode node) {
        while (node.has("$ref")) node = SPEC.at(node.get("$ref").asText().substring(1));
        return node;
    }
    private static void validate(JsonNode schema, JsonNode value, String location) {
        ObjectNode root = JSON.createObjectNode();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.set("components", SPEC.get("components"));
        root.setAll((ObjectNode) schema);
        var config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        var errors = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root, config).validate(value);
        assertTrue(errors.isEmpty(), "Schema violation at " + location); // No response/token values in logs.
    }
    public static void verify(String path, String method, MockHttpServletResponse response) throws Exception {
        JsonNode operation = SPEC.path("paths").path(path).path(method.toLowerCase());
        assertFalse(operation.isMissingNode(), "Unknown operation " + method + " " + path);
        JsonNode declared = operation.path("responses").path(Integer.toString(response.getStatus()));
        assertFalse(declared.isMissingNode(), "Undeclared HTTP status at " + path);
        declared = resolve(declared);
        var headers = declared.path("headers").fields();
        while (headers.hasNext()) {
            var header = headers.next(); var definition = resolve(header.getValue());
            var actual = response.getHeaders(header.getKey());
            if (definition.path("required").asBoolean()) assertFalse(actual.isEmpty(), "Missing " + header.getKey());
            for (String value : actual) {
                JsonNode node = JSON.getNodeFactory().textNode(value);
                if ("integer".equals(definition.path("schema").path("type").asText()))
                    node = JSON.getNodeFactory().numberNode(Long.parseLong(value));
                validate(definition.path("schema"), node, header.getKey());
            }
        }
        if (!declared.has("content")) {
            assertEquals(0, response.getContentAsByteArray().length, "Body forbidden at " + path); return;
        }
        String type = response.getContentType();
        assertNotNull(type, "Missing content type");
        JsonNode content = declared.path("content").path(type.split(";")[0].trim());
        assertFalse(content.isMissingNode(), "Unexpected content type at " + path);
        validate(content.path("schema"), JSON.readTree(response.getContentAsByteArray()), path);
    }
}
