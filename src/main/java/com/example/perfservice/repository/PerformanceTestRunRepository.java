package com.example.perfservice.repository;

import com.example.perfservice.entity.PerformanceTestRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PerformanceTestRunRepository extends JpaRepository<PerformanceTestRun, Long> {

    List<PerformanceTestRun> findByUserIdOrderByStartedAtDesc(Long userId);

    // Scoped findById — prevents user A from reading user B's run by guessing an ID
    Optional<PerformanceTestRun> findByIdAndUserId(Long id, Long userId);

    @Query("SELECT r FROM PerformanceTestRun r WHERE r.status IN ('PENDING','RUNNING')")
    List<PerformanceTestRun> findActiveRuns();
}