package com.example.perfservice.entity;

import com.example.perfservice.constants.PerformanceTestType;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "performance_test_runs")
@Data
public class PerformanceTestRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // User-provided label for this test — no coupling to the main app's step IDs
    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String resolvedUrl;

    @Column(nullable = false)
    private String resolvedMethod;

    @Column(columnDefinition = "TEXT")
    private String resolvedHeadersJson;

    @Column(columnDefinition = "TEXT")
    private String resolvedBodyJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PerformanceTestType testType;

    // ── Config ────────────────────────────────────────────────────────────────

    @Column(nullable = false)
    private Integer virtualUsers;

    private Integer durationSeconds;
    private Integer stressRampStep;
    private Integer stressRampIntervalSeconds;
    private Integer spikeUsers;
    private Integer spikeDurationSeconds;
    private Integer warmupSeconds;
    private Integer cooldownSeconds;
    private Integer soakDurationSeconds;

    // ── Status ────────────────────────────────────────────────────────────────

    @Column(nullable = false)
    private String status; // PENDING | RUNNING | COMPLETED | FAILED | CANCELLED

    @Column(nullable = false)
    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    // ── Summary stats ─────────────────────────────────────────────────────────

    private Long totalRequests;
    private Long successfulRequests;
    private Long failedRequests;
    private Double errorRatePercent;
    private Double avgLatencyMs;
    private Double minLatencyMs;
    private Double maxLatencyMs;
    private Double p50LatencyMs;
    private Double p95LatencyMs;
    private Double p99LatencyMs;
    private Double throughputRps;

    // Stress only: the user count where error rate crossed the threshold
    private Integer stressBreakingPointUsers;

    @Column(columnDefinition = "TEXT")
    private String errorSummary;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}