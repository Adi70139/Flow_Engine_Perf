package com.example.perfservice.repository;

import com.example.perfservice.entity.PerformanceTestSample;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PerformanceTestSampleRepository extends JpaRepository<PerformanceTestSample, Long> {

    List<PerformanceTestSample> findByRunIdOrderByFiredAt(Long runId);

    @Query(value = "SELECT latency_ms FROM performance_test_samples WHERE run_id = :runId ORDER BY latency_ms",
            nativeQuery = true)
    List<Long> findLatenciesSorted(@Param("runId") Long runId);

    long countByRunId(Long runId);
    @Query(value = """
            SELECT
                virtual_user_id                                         AS userId,
                COUNT(*)                                                AS totalRequests,
                SUM(CASE WHEN success = true THEN 1 ELSE 0 END)        AS successfulRequests,
                SUM(CASE WHEN success = false THEN 1 ELSE 0 END)       AS failedRequests,
                ROUND(AVG(latency_ms)::numeric, 2)                     AS avgLatencyMs,
                MIN(latency_ms)                                         AS minLatencyMs,
                MAX(latency_ms)                                         AS maxLatencyMs
            FROM performance_test_samples
            WHERE run_id = :runId
            GROUP BY virtual_user_id
            ORDER BY virtual_user_id
            """, nativeQuery = true)
    List<java.util.Map<String, Object>> getUserStats(@Param("runId") Long runId);
}