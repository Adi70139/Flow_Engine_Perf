package com.example.perfservice.dto;

import com.example.perfservice.constants.PerformanceTestType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class PerformanceTestRequest {

    // ── Target — one of these two approaches ─────────────────────────────────

    // Option A: reference a saved API (preferred — reuse across multiple runs)
    private Long apiId;

    // Option B: provide inline (quick one-off test, no need to save)
    private String url;
    private String method = "GET";
    private java.util.Map<String, String> headers;
    private String body;

    // Multiple payloads for round-robin across virtual users.
    // Works with both apiId and inline. Overrides the saved API's payloadList if set here.
    private java.util.List<String> payloadList;

    private String name; // label shown in history

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