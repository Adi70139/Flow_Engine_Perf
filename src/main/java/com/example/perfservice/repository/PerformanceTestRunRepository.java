package com.example.perfservice.repository;

import com.example.perfservice.entity.PerformanceTestRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PerformanceTestRunRepository extends JpaRepository<PerformanceTestRun, Long> {

    List<PerformanceTestRun> findAllByOrderByStartedAtDesc();

    @Query("SELECT r FROM PerformanceTestRun r WHERE r.status IN ('PENDING','RUNNING')")
    List<PerformanceTestRun> findActiveRuns();
}