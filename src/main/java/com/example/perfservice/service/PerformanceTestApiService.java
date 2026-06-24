package com.example.perfservice.service;

import com.example.perfservice.dto.PerformanceTestApiRequest;
import com.example.perfservice.entity.PerformanceTestApi;
import com.example.perfservice.repository.PerformanceTestApiRepository;
import com.example.perfservice.security.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PerformanceTestApiService {

    private final PerformanceTestApiRepository apiRepository;
    private final ObjectMapper objectMapper;

    public PerformanceTestApi create(PerformanceTestApiRequest request) {
        Long userId = SecurityUtils.requireUserId();
        PerformanceTestApi api = new PerformanceTestApi();
        api.setUserId(userId);
        applyRequest(api, request);
        log.info("[PerfApi] Created '{}' for userId={}", api.getName(), userId);
        return apiRepository.save(api);
    }

    public List<PerformanceTestApi> listForCurrentUser() {
        return apiRepository.findByUserIdOrderByCreatedAtDesc(SecurityUtils.requireUserId());
    }

    public PerformanceTestApi getById(Long id) {
        return apiRepository.findByIdAndUserId(id, SecurityUtils.requireUserId())
                .orElseThrow(() -> new IllegalArgumentException("API not found: " + id));
    }

    public PerformanceTestApi update(Long id, PerformanceTestApiRequest request) {
        PerformanceTestApi api = getById(id);
        applyRequest(api, request);
        api.setUpdatedAt(LocalDateTime.now());
        log.info("[PerfApi] Updated '{}' id={}", api.getName(), id);
        return apiRepository.save(api);
    }

    public void delete(Long id) {
        PerformanceTestApi api = getById(id);
        apiRepository.delete(api);
        log.info("[PerfApi] Deleted id={}", id);
    }

    private void applyRequest(PerformanceTestApi api, PerformanceTestApiRequest request) {
        api.setName(request.getName());
        api.setDescription(request.getDescription());
        api.setUrl(request.getUrl());
        api.setMethod(request.getMethod().toUpperCase());
        try {
            api.setHeadersJson(request.getHeaders() != null
                    ? objectMapper.writeValueAsString(request.getHeaders()) : null);
        } catch (Exception e) {
            api.setHeadersJson(null);
        }
        api.setBodyJson(request.getBody());
        try {
            api.setPayloadListJson(request.getPayloadList() != null && !request.getPayloadList().isEmpty()
                    ? objectMapper.writeValueAsString(request.getPayloadList()) : null);
        } catch (Exception e) {
            api.setPayloadListJson(null);
        }
    }
}