package com.example.perfservice.dto;

import lombok.Data;
import java.util.List;

/**
 * One step in a prerequisite chain attached to a PerformanceTestApi.
 * Each virtual user runs all prerequisite steps in order before firing the target request.
 * Captured fields are injected into subsequent steps and the target request as {{PLACEHOLDER}}.
 *
 * Example chain for an authenticated API:
 * [
 *   {
 *     "apiId": 1,
 *     "name": "Login",
 *     "captures": [
 *       { "field": "accessToken", "as": "AUTH_TOKEN" }
 *     ]
 *   }
 * ]
 *
 * Target API headers then use: {"Authorization": "Bearer {{AUTH_TOKEN}}"}
 */
@Data
public class PrerequisiteStep {

    // ID of an existing saved PerformanceTestApi to use as the prereq step.
    // The saved API's url/method/headers/body are used as-is, with any {{PLACEHOLDER}}
    // values resolved from captures of earlier steps in the chain.
    private Long apiId;

    // Display name shown in logs and run details — defaults to the saved API's name if null
    private String name;

    // Which fields to extract from this step's response body and what to name them.
    // Extracted values are available as {{AS_NAME}} in all subsequent steps and the target.
    private List<FieldCapture> captures;

    // Index in the round-robin payload list to use (same semantics as the main API's payloadList).
    // Null = use the saved API's default body as-is.
    // Set to -1 to use the same index as the target API (same virtual user slot).
    private Integer payloadIndex;

    @Data
    public static class FieldCapture {
        // Dot-notation field path in the response body (e.g. "accessToken", "data.token")
        private String field;
        // Placeholder name to use in subsequent steps (e.g. "AUTH_TOKEN" → {{AUTH_TOKEN}})
        private String as;
    }
}