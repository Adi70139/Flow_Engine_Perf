package com.example.perfservice.dto;

import com.example.perfservice.constants.PerformanceTestType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class PerformanceTestRequest {

    // ── Target API — user provides fully resolved values, no placeholders ─────

    @NotBlank(message = "url is required")
    private String url;

    @NotBlank(message = "method is required (GET, POST, PUT, DELETE, PATCH)")
    private String method = "GET";

    // Optional — key/value pairs e.g. {"Content-Type": "application/json", "Authorization": "Bearer ..."}
    private Map<String, String> headers;

    // Optional — raw request body string
    private String body;

    // Optional label — shown in history/results so you know what was tested
    private String name;

    // ── Test type ─────────────────────────────────────────────────────────────

    @NotNull
    private PerformanceTestType testType;

    @Min(1)
    private Integer virtualUsers = 10;

    @Min(1)
    private Integer durationSeconds = 30;

    // Stress
    private Integer stressRampStep = 5;
    private Integer stressRampIntervalSeconds = 10;

    // Spike
    private Integer spikeUsers = 100;
    private Integer spikeDurationSeconds = 10;
    private Integer warmupSeconds = 10;
    private Integer cooldownSeconds = 10;

    // Soak
    private Integer soakDurationSeconds = 300;
}