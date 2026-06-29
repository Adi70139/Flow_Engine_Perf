package com.example.perfservice.service;

import com.example.perfservice.dto.PerformanceTestApiRequest;
import com.example.perfservice.entity.PerformanceTestApi;
import com.example.perfservice.repository.PerformanceTestApiRepository;
import com.example.perfservice.security.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import okhttp3.*;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PerformanceTestApiService {

    private final PerformanceTestApiRepository apiRepository;
    private final ObjectMapper objectMapper;
    private final OkHttpClient okHttpClient;

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
        try {
            api.setPrerequisiteChainJson(request.getPrerequisiteChain() != null && !request.getPrerequisiteChain().isEmpty()
                    ? objectMapper.writeValueAsString(request.getPrerequisiteChain()) : null);
        } catch (Exception e) {
            api.setPrerequisiteChainJson(null);
        }
    }


    public com.example.perfservice.dto.SingleRequestResult testOne(Long id) {
        PerformanceTestApi api = getById(id);
        com.example.perfservice.dto.SingleRequestResult result = new com.example.perfservice.dto.SingleRequestResult();
        long start = System.currentTimeMillis();

        try {
            okhttp3.Request.Builder rb = new okhttp3.Request.Builder().url(api.getUrl());

            if (api.getHeadersJson() != null && !api.getHeadersJson().isBlank()) {
                objectMapper.readValue(api.getHeadersJson(), java.util.Map.class)
                        .forEach((k, v) -> rb.header(String.valueOf(k), String.valueOf(v)));
            }

            String method = api.getMethod().toUpperCase();
            String body = api.getBodyJson();
            // Use first payload from list if available
            if (api.getPayloadListJson() != null && !api.getPayloadListJson().isBlank()) {
                java.util.List<String> payloads = objectMapper.readValue(api.getPayloadListJson(),
                        new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {});
                if (!payloads.isEmpty()) body = payloads.get(0);
            }

            okhttp3.RequestBody requestBody = null;
            if (body != null && !body.isBlank() && !method.equals("GET")) {
                requestBody = okhttp3.RequestBody.create(body,
                        okhttp3.MediaType.get("application/json; charset=utf-8"));
            }
            rb.method(method, requestBody);

            try (okhttp3.Response response = okHttpClient.newCall(rb.build()).execute()) {
                result.setStatusCode(response.code());
                result.setLatencyMs(System.currentTimeMillis() - start);
                result.setSuccess(response.isSuccessful());
                String responseBody = response.body() != null ? response.body().string() : "";
                result.setResponseBody(responseBody);

                // Flatten response to dot-notation fields so UI can show available capture paths
                if (!responseBody.isBlank()) {
                    try {
                        com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(responseBody);
                        java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
                        flattenNode(root, "", fields);
                        result.setAvailableFields(fields);
                    } catch (Exception ignored) {
                        // Non-JSON response — no fields to extract
                    }
                }
            }
        } catch (Exception e) {
            result.setLatencyMs(System.currentTimeMillis() - start);
            result.setSuccess(false);
            result.setError(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return result;
    }

    private void flattenNode(com.fasterxml.jackson.databind.JsonNode node, String prefix,
                             java.util.Map<String, String> map) {
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                String path = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
                flattenNode(e.getValue(), path, map);
            });
        } else if (node.isArray()) {
            if (node.size() == 0) {
                map.put(prefix, "[empty array]");
            } else {
                for (int i = 0; i < node.size(); i++) {
                    flattenNode(node.get(i), prefix + "[" + i + "]", map);
                }
                // Also add collapsed key pointing to first value so user sees "data.roles" not just "data.roles[0]"
                if (!map.containsKey(prefix)) {
                    map.put(prefix, "[array] " + (node.get(0).isValueNode() ? node.get(0).asText() : "..."));
                }
            }
        } else if (node.isValueNode()) {
            map.put(prefix, node.isNull() ? "" : node.asText());
        }
    }
}