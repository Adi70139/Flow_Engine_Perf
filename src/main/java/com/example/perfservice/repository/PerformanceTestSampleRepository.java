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
}