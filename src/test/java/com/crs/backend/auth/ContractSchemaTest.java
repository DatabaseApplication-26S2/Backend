package com.crs.backend.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContractSchemaTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static java.util.Set<com.networknt.schema.ValidationMessage> errors(String name, String body) throws Exception {
        ObjectNode root;
        try (var input = ContractSchemaTest.class.getResourceAsStream("/contract/notion-schemas.json")) {
            root = (ObjectNode) JSON.readTree(input);
        }
        root.put("$ref", "#/components/schemas/" + name);
        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(root);
        return schema.validate(JSON.readTree(body));
    }
    static void assertValid(String name, String body) throws Exception {
        assertTrue(errors(name, body).isEmpty(), "Response violates " + name);
    }
    @Test void validatorRejectsTypesEnumsRangesUnknownPropertiesAndConditionalViolations() throws Exception {
        String valid = """
            {"accessToken":"jwt","tokenType":"Bearer","expiresIn":900,
             "user":{"userId":"s","name":"Student","role":"STUDENT"},
             "session":{"expiresAt":"2026-11-25T00:00:00Z","admittedAt":null}}
            """;
        assertValid("TokenResponse", valid);
        assertFalse(errors("TokenResponse", valid.replace("\"expiresIn\":900", "\"expiresIn\":\"900\"")).isEmpty());
        assertFalse(errors("TokenResponse", valid.replace("STUDENT", "UNKNOWN")).isEmpty());
        assertFalse(errors("TokenResponse", valid.replace(":900", ":901")).isEmpty());
        assertFalse(errors("TokenResponse", valid.replace("\"accessToken\"", "\"refreshToken\":\"forbidden\",\"accessToken\"")).isEmpty());
        assertFalse(errors("TokenResponse", valid.replace("STUDENT", "PROFESSOR")
            .replace("\"admittedAt\":null", "\"admittedAt\":\"2026-11-18T00:00:00Z\"")).isEmpty());
        assertFalse(errors("ProblemDetails", "{\"type\":\"urn:crs:problem:E09\",\"title\":\"Invalid\",\"status\":400,\"detail\":\"Invalid\",\"code\":\"E09\"}").isEmpty());
    }
}
