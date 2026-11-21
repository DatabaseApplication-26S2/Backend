package com.crs.backend.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** Proves the shared validator rejects contract violations, so passing tests elsewhere are not vacuous. */
class OpenApiResponsesTest {
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String VALID_PROBLEM = "{\"type\":\"urn:crs:problem:E02\",\"title\":\"Unauthorized\","
        + "\"status\":401,\"detail\":\"Access token is invalid\",\"code\":\"E02\"}";

    private static MockHttpServletResponse response(int status, String contentType, String body) throws Exception {
        var response = new MockHttpServletResponse();
        response.setStatus(status);
        if (contentType != null) response.setContentType(contentType);
        if (body != null) response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        return response;
    }
    /** Valid 401 of GET /tickets/{ticketId}: WWW-Authenticate (required) + Cache-Control + ProblemDetails. */
    private static MockHttpServletResponse valid401(String body) throws Exception {
        var response = response(401, PROBLEM_JSON, body);
        response.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
        response.setHeader("Cache-Control", "no-store");
        return response;
    }

    @Test void validResponsePasses() throws Exception {
        assertDoesNotThrow(() -> OpenApiResponses.verify("/tickets/{ticketId}", "get", valid401(VALID_PROBLEM)));
    }
    @Test void validNoContentLogoutPasses() throws Exception {
        var response = response(204, null, null);
        response.addHeader("Set-Cookie", "__Host-crs_refresh=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Strict");
        response.setHeader("Cache-Control", "no-store");
        assertDoesNotThrow(() -> OpenApiResponses.verify("/auth/logout", "post", response));
    }
    @Test void unknownOperationIsRejected() throws Exception {
        var response = valid401(VALID_PROBLEM);
        assertThrows(AssertionError.class, () -> OpenApiResponses.verify("/no-such-path", "get", response));
        assertThrows(AssertionError.class, () -> OpenApiResponses.verify("/tickets/{ticketId}", "delete", response));
    }
    @Test void undeclaredStatusIsRejected() throws Exception {
        var response = response(418, PROBLEM_JSON, VALID_PROBLEM);
        assertThrows(AssertionError.class, () -> OpenApiResponses.verify("/tickets/{ticketId}", "get", response));
    }
    @Test void missingRequiredHeaderIsRejected() throws Exception {
        var response = response(401, PROBLEM_JSON, VALID_PROBLEM); // no WWW-Authenticate
        response.setHeader("Cache-Control", "no-store");
        assertThrows(AssertionError.class, () -> OpenApiResponses.verify("/tickets/{ticketId}", "get", response));
    }
    @Test void undeclaredContentTypeIsRejected() throws Exception {
        var response = response(401, "text/plain", VALID_PROBLEM);
        response.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
        response.setHeader("Cache-Control", "no-store");
        assertThrows(AssertionError.class, () -> OpenApiResponses.verify("/tickets/{ticketId}", "get", response));
    }
    @Test void missingContentTypeIsRejected() throws Exception {
        var response = response(401, null, VALID_PROBLEM);
        response.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
        response.setHeader("Cache-Control", "no-store");
        assertThrows(AssertionError.class, () -> OpenApiResponses.verify("/tickets/{ticketId}", "get", response));
    }
    @Test void bodyWithWrongTypeIsRejected() throws Exception {
        String wrongType = VALID_PROBLEM.replace("\"status\":401", "\"status\":\"401\"");
        assertThrows(AssertionError.class, () -> OpenApiResponses.verify("/tickets/{ticketId}", "get", valid401(wrongType)));
    }
    // ProblemDetails schema constraints: ProblemCode enum, status range 400..599, and allOf if/then conditions.
    @Test void enumViolationIsRejected() throws Exception {
        String badCode = VALID_PROBLEM.replace("\"code\":\"E02\"", "\"code\":\"E00\"");
        assertThrows(AssertionError.class, () -> OpenApiResponses.verify("/tickets/{ticketId}", "get", valid401(badCode)));
    }
    @Test void rangeViolationIsRejected() throws Exception {
        String belowMinimum = VALID_PROBLEM.replace("\"status\":401", "\"status\":399");
        assertThrows(AssertionError.class, () -> OpenApiResponses.verify("/tickets/{ticketId}", "get", valid401(belowMinimum)));
    }
    @Test void conditionalSchemaE09WithoutViolationsIsRejected() throws Exception {
        // 400 is declared for GET /tickets/{ticketId}; E09 then requires `violations`.
        String body = "{\"type\":\"urn:crs:problem:E09\",\"title\":\"Bad request\",\"status\":400,"
            + "\"detail\":\"Invalid ticket id\",\"code\":\"E09\"}";
        var response = response(400, PROBLEM_JSON, body);
        response.setHeader("Cache-Control", "no-store");
        assertThrows(AssertionError.class, () -> OpenApiResponses.verify("/tickets/{ticketId}", "get", response));
    }
    @Test void conditionalSchemaReasonStatusMismatchIsRejected() throws Exception {
        // reason LOGIN_PROOF_INVALID requires status 403, but the body says 401 (and the response is a declared 401).
        String body = VALID_PROBLEM.replace("\"code\":\"E02\"", "\"code\":\"E02\",\"reason\":\"LOGIN_PROOF_INVALID\"");
        assertThrows(AssertionError.class, () -> OpenApiResponses.verify("/tickets/{ticketId}", "get", valid401(body)));
    }
    @Test void bodyWithExtraPropertyIsRejected() throws Exception {
        String extra = VALID_PROBLEM.replace("\"code\":\"E02\"", "\"code\":\"E02\",\"unexpected\":true");
        assertThrows(AssertionError.class, () -> OpenApiResponses.verify("/tickets/{ticketId}", "get", valid401(extra)));
    }
    @Test void bodyMissingRequiredPropertyIsRejected() throws Exception {
        String missing = VALID_PROBLEM.replace(",\"code\":\"E02\"", "");
        assertThrows(AssertionError.class, () -> OpenApiResponses.verify("/tickets/{ticketId}", "get", valid401(missing)));
    }
    @Test void bodyOnResponseWithoutContentIsRejected() throws Exception {
        var response = response(204, PROBLEM_JSON, VALID_PROBLEM);
        response.addHeader("Set-Cookie", "__Host-crs_refresh=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Strict");
        response.setHeader("Cache-Control", "no-store");
        assertThrows(AssertionError.class, () -> OpenApiResponses.verify("/auth/logout", "post", response));
    }
}
