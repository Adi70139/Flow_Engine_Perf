package com.example.perfservice.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "performance_test_samples",
        indexes = {
                @Index(name = "idx_sample_run_id", columnList = "run_id"),
                @Index(name = "idx_sample_fired_at", columnList = "firedAt")
        })
@Data
public class PerformanceTestSample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    private Integer virtualUserId;

    @Column(nullable = false)
    private LocalDateTime firedAt;

    @Column(nullable = false)
    private Long latencyMs;

    @Column(nullable = false)
    private Integer statusCode;

    @Column(nullable = false)
    private Boolean success;

    @Column(length = 500)
    private String errorDetail;

    // For stress tests: user count at the moment this request was fired
    private Integer concurrentUsers;
}