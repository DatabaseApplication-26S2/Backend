package com.crs.backend.auth;
import com.crs.backend.common.ApiProblem;
import java.util.Map;

/** Authentication failures share the team's ProblemDetails renderer. */
public class AuthFailure extends RuntimeException {
    private final ApiProblem problem;
    public AuthFailure(int status, String code) { this(ApiProblem.fromAuthFailure(status, code)); }
    private AuthFailure(ApiProblem problem) { super(problem.code()); this.problem = problem; }
    public static AuthFailure pending(long id) {
        return new AuthFailure(new ApiProblem(409, "E03", null, null, id, null));
    }
    public static AuthFailure invalid(ApiProblem.Source source, String field) {
        return new AuthFailure(ApiProblem.invalid(source, field, "Invalid request format"));
    }
    public ApiProblem problem() { return problem; }
    public int status() { return problem.status(); }
    public String code() { return problem.code(); }
    public Map<String, Object> body() { return problem.toBody(); }
}
