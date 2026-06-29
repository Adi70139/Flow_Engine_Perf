package com.example.perfservice.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * A saved API configuration owned by a user.
 * Think of it as a "test target" — the user defines it once,
 * then runs performance tests against it multiple times.
 * payloadListJson stores a JSON array of body strings for round-robin
 * distribution across virtual users (different credentials per user, etc.)
 */
@Entity
@Table(name = "performance_test_apis",
        indexes = @Index(name = "idx_perf_api_user_id", columnList = "user_id"))
@Data
public class PerformanceTestApi {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(nullable = false)
    private String url;

    @Column(nullable = false)
    private String method;

    @Column(columnDefinition = "TEXT")
    private String headersJson;

    // Single body — used when all virtual users send the same payload
    @Column(columnDefinition = "TEXT")
    private String bodyJson;

    // JSON array of body strings — cycled round-robin across virtual users.
    // If set, this takes precedence over bodyJson.
    // e.g. ["{\"username\":\"u1\",\"password\":\"p1\"}", "{\"username\":\"u2\",...}"]
    @Column(columnDefinition = "TEXT")
    private String payloadListJson;

    // JSON array of prerequisite API steps, executed in order by each virtual user
    // before the target request. Outputs captured from each step are injected into
    // the next step and into the target request as {{placeholder}} values.
    //
    // Format: [{
    //   "apiId": 1,                          // saved API to run as prereq
    //   "name": "Login",                     // display name
    //   "captures": [                        // fields to extract from response
    //     { "field": "accessToken", "as": "AUTH_TOKEN" }
    //   ]
    // }]
    @Column(columnDefinition = "TEXT")
    private String prerequisiteChainJson;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;
}