package com.example.perfservice.repository;

import com.example.perfservice.entity.PerformanceTestApi;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PerformanceTestApiRepository extends JpaRepository<PerformanceTestApi, Long> {

    List<PerformanceTestApi> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<PerformanceTestApi> findByIdAndUserId(Long id, Long userId);
}