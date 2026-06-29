package com.example.perfservice.service;

import com.example.perfservice.dto.PerformanceTestRequest;
import com.example.perfservice.dto.PrerequisiteStep;
import com.example.perfservice.entity.PerformanceTestRun;
import com.example.perfservice.entity.PerformanceTestSample;
import com.example.perfservice.repository.PerformanceTestRunRepository;
import com.example.perfservice.repository.PerformanceTestSampleRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
@Slf4j
public class PerformanceTestService {

    private final PerformanceTestRunRepository runRepository;
    private final PerformanceTestSampleRepository sampleRepository;
    private final com.example.perfservice.repository.PerformanceTestApiRepository apiRepository;
    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");
    private static final double STRESS_ERROR_THRESHOLD = 0.05; // 5%

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final Map<Long, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    // ── Public API ────────────────────────────────────────────────────────────

    public PerformanceTestRun start(PerformanceTestRequest request) throws JsonProcessingException {
        Long userId = com.example.perfservice.security.SecurityUtils.requireUserId();

        // Resolve API config — from saved API or inline fields
        String url, method, headersJson, bodyJson, payloadListJson, prerequisiteChainJson;
        Long apiId = null;

        if (request.getApiId() != null) {
            com.example.perfservice.entity.PerformanceTestApi api =
                    apiRepository.findByIdAndUserId(request.getApiId(), userId)
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "API not found: " + request.getApiId()));
            url          = api.getUrl();
            method       = api.getMethod();
            headersJson  = api.getHeadersJson();
            bodyJson     = api.getBodyJson();
            // Request-level payloadList overrides the saved API's — lets you test
            // the same endpoint with different data sets without editing the saved API
            payloadListJson = resolvePayloadList(request, api);
            // Snapshot the prereq chain — request-level override takes precedence over saved API
            String reqChain = request.getPrerequisiteChain() != null && !request.getPrerequisiteChain().isEmpty()
                    ? objectMapper.writeValueAsString(request.getPrerequisiteChain()) : null;
            prerequisiteChainJson = reqChain != null ? reqChain : api.getPrerequisiteChainJson();
            apiId = api.getId();
            log.info("[PerfTest] Using saved API '{}' (id={}) for run", api.getName(), apiId);
        } else {
            if (request.getUrl() == null || request.getUrl().isBlank()) {
                throw new IllegalArgumentException("Either apiId or url must be provided");
            }
            url    = request.getUrl();
            method = request.getMethod() != null ? request.getMethod().toUpperCase() : "GET";
            try {
                headersJson = request.getHeaders() != null
                        ? objectMapper.writeValueAsString(request.getHeaders()) : null;
            } catch (Exception e) { headersJson = null; }
            bodyJson        = request.getBody();
            payloadListJson = resolvePayloadList(request, null);
            prerequisiteChainJson = request.getPrerequisiteChain() != null && !request.getPrerequisiteChain().isEmpty()
                    ? objectMapper.writeValueAsString(request.getPrerequisiteChain()) : null;
        }

        PerformanceTestRun run = buildRun(request, userId, apiId, url, method,
                headersJson, bodyJson, payloadListJson, prerequisiteChainJson);
        run = runRepository.save(run);

        final Long runId = run.getId();
        final PerformanceTestRun savedRun = run;
        cancelFlags.put(runId, new AtomicBoolean(false));

        log.info("[PerfTest] Starting {} runId={} name='{}' url={} users={} duration={}s",
                request.getTestType(), runId, request.getName(),
                request.getUrl(), request.getVirtualUsers(), request.getDurationSeconds());

        Thread testThread = new Thread(() -> {
            try {
                updateStatus(runId, "RUNNING");
                switch (request.getTestType()) {
                    case LOAD   -> runLoad(savedRun, request);
                    case STRESS -> runStress(savedRun, request);
                    case SPIKE  -> runSpike(savedRun, request);
                    case SOAK   -> runSoak(savedRun, request);
                }
                finalizeRun(runId);
            } catch (Exception e) {
                log.error("[PerfTest] runId={} failed: {}", runId, e.getMessage(), e);
                updateStatus(runId, "FAILED");
                broadcastEvent(runId, "error", Map.of("message", e.getMessage()));
            } finally {
                cancelFlags.remove(runId);
                closeEmitters(runId);
            }
        }, "perf-test-" + runId);
        testThread.setDaemon(true);
        testThread.start();

        return savedRun;
    }

    public void cancel(Long runId) {
        Long userId = com.example.perfservice.security.SecurityUtils.requireUserId();
        // Verify ownership before allowing cancel
        runRepository.findByIdAndUserId(runId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        AtomicBoolean flag = cancelFlags.get(runId);
        if (flag == null) throw new IllegalStateException("No active test with runId: " + runId);
        flag.set(true);
        log.info("[PerfTest] Cancel requested for runId={} by userId={}", runId, userId);
    }

    public PerformanceTestRun getResult(Long runId) {
        Long userId = com.example.perfservice.security.SecurityUtils.requireUserId();
        return runRepository.findByIdAndUserId(runId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
    }

    public List<java.util.Map<String, Object>> getUserStats(Long runId) {
        Long userId = com.example.perfservice.security.SecurityUtils.requireUserId();
        runRepository.findByIdAndUserId(runId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        return sampleRepository.getUserStats(runId);
    }

    public List<PerformanceTestSample> getSamples(Long runId) {
        Long userId = com.example.perfservice.security.SecurityUtils.requireUserId();
        // ownership check before loading samples
        runRepository.findByIdAndUserId(runId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        return sampleRepository.findByRunIdOrderByFiredAt(runId);
    }

    public List<PerformanceTestRun> getHistory() {
        Long userId = com.example.perfservice.security.SecurityUtils.requireUserId();
        return runRepository.findByUserIdOrderByStartedAtDesc(userId);
    }

    private PerformanceTestRun buildRun(PerformanceTestRequest req, Long userId,
                                        Long apiId, String url, String method,
                                        String headersJson, String bodyJson,
                                        String payloadListJson, String prerequisiteChainJson) {
        PerformanceTestRun run = new PerformanceTestRun();
        run.setUserId(userId);
        run.setApiId(apiId);
        run.setName(req.getName() != null ? req.getName() : method + " " + url);
        run.setResolvedUrl(url);
        run.setResolvedMethod(method);
        run.setResolvedHeadersJson(headersJson);
        run.setResolvedBodyJson(bodyJson);
        run.setPayloadListJson(payloadListJson);
        run.setPrerequisiteChainJson(prerequisiteChainJson);
        run.setTestType(req.getTestType());
        run.setVirtualUsers(req.getVirtualUsers());
        run.setDurationSeconds(req.getDurationSeconds());
        run.setStressRampStep(req.getStressRampStep());
        run.setStressRampIntervalSeconds(req.getStressRampIntervalSeconds());
        run.setSpikeUsers(req.getSpikeUsers());
        run.setSpikeDurationSeconds(req.getSpikeDurationSeconds());
        run.setWarmupSeconds(req.getWarmupSeconds());
        run.setCooldownSeconds(req.getCooldownSeconds());
        run.setSoakDurationSeconds(req.getSoakDurationSeconds());
        run.setStatus("PENDING");
        run.setStartedAt(LocalDateTime.now());
        return run;
    }

    /**
     * Resolves which payload list to use. Request-level payloadList takes precedence
     * over the saved API's payloadList — lets you override without editing the saved API.
     */
    private String resolvePayloadList(PerformanceTestRequest request,
                                      com.example.perfservice.entity.PerformanceTestApi api) {
        // Request-level overrides saved API
        if (request.getPayloadList() != null && !request.getPayloadList().isEmpty()) {
            try { return objectMapper.writeValueAsString(request.getPayloadList()); }
            catch (Exception e) { return null; }
        }
        // Fall back to saved API's payload list
        if (api != null) return api.getPayloadListJson();
        return null;
    }

    public SseEmitter subscribe(Long runId) {
        Long userId = com.example.perfservice.security.SecurityUtils.requireUserId();
        runRepository.findByIdAndUserId(runId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(runId, emitter));
        emitter.onTimeout(() -> removeEmitter(runId, emitter));
        emitter.onError(e -> removeEmitter(runId, emitter));
        runRepository.findByIdAndUserId(runId, userId).ifPresent(run -> {
            try { emitter.send(SseEmitter.event().name("status").data(run.getStatus())); }
            catch (Exception ignored) {}
        });
        return emitter;
    }

    // ── Test types ────────────────────────────────────────────────────────────

    /**
     * LOAD: N virtual users fire requests back-to-back for durationSeconds.
     * Models steady sustained traffic. Key output: p95/p99 latency under known concurrency.
     */
    private void runLoad(PerformanceTestRun run, PerformanceTestRequest req) throws Exception {
        log.info("[PerfTest] LOAD runId={} users={} duration={}s",
                run.getId(), req.getVirtualUsers(), req.getDurationSeconds());
        runConcurrently(run, req.getVirtualUsers(), req.getDurationSeconds() * 1000L);
    }

    /**
     * STRESS: Start at virtualUsers, add stressRampStep users every stressRampIntervalSeconds.
     * Stop when error rate > 5%. Key output: stressBreakingPointUsers — the user ceiling.
     */
    private void runStress(PerformanceTestRun run, PerformanceTestRequest req) throws Exception {
        log.info("[PerfTest] STRESS runId={} startUsers={} rampStep={} interval={}s",
                run.getId(), req.getVirtualUsers(), req.getStressRampStep(),
                req.getStressRampIntervalSeconds());

        int currentUsers = req.getVirtualUsers();
        long maxMs = (req.getDurationSeconds() != null ? req.getDurationSeconds() : 300) * 1000L;
        long startTime = System.currentTimeMillis();
        Integer breakingPoint = null;
        AtomicBoolean cancelled = cancelFlags.get(run.getId());

        while (!cancelled.get() && (System.currentTimeMillis() - startTime) < maxMs) {
            log.info("[PerfTest] STRESS runId={} → {} users", run.getId(), currentUsers);
            broadcastEvent(run.getId(), "ramp", Map.of("users", currentUsers));

            List<PerformanceTestSample> levelSamples =
                    runConcurrentlyCollect(run, currentUsers, req.getStressRampIntervalSeconds() * 1000L);

            long total = levelSamples.size();
            long failed = levelSamples.stream().filter(s -> !s.getSuccess()).count();
            double errorRate = total > 0 ? (double) failed / total : 0;

            log.info("[PerfTest] STRESS runId={} users={} errorRate={:.1f}%",
                    run.getId(), currentUsers, errorRate * 100);

            if (errorRate >= STRESS_ERROR_THRESHOLD) {
                breakingPoint = currentUsers;
                log.info("[PerfTest] STRESS breaking point: {} users ({:.1f}% error rate)",
                        currentUsers, errorRate * 100);
                broadcastEvent(run.getId(), "breaking_point",
                        Map.of("users", currentUsers, "errorRatePct", errorRate * 100));
                break;
            }
            currentUsers += req.getStressRampStep();
        }

        if (breakingPoint != null) {
            final int bp = breakingPoint;
            runRepository.findById(run.getId()).ifPresent(r -> {
                r.setStressBreakingPointUsers(bp);
                runRepository.save(r);
            });
        }
    }

    /**
     * SPIKE: warmup at base users → burst to spikeUsers → cooldown back to base.
     * Key output: compare p99 during cooldown vs warmup — good API = they converge back.
     */
    private void runSpike(PerformanceTestRun run, PerformanceTestRequest req) throws Exception {
        log.info("[PerfTest] SPIKE runId={} base={} spike={}",
                run.getId(), req.getVirtualUsers(), req.getSpikeUsers());

        broadcastEvent(run.getId(), "phase", Map.of("phase", "warmup", "users", req.getVirtualUsers()));
        runConcurrently(run, req.getVirtualUsers(), req.getWarmupSeconds() * 1000L);

        broadcastEvent(run.getId(), "phase", Map.of("phase", "spike", "users", req.getSpikeUsers()));
        runConcurrently(run, req.getSpikeUsers(), req.getSpikeDurationSeconds() * 1000L);

        broadcastEvent(run.getId(), "phase", Map.of("phase", "cooldown", "users", req.getVirtualUsers()));
        runConcurrently(run, req.getVirtualUsers(), req.getCooldownSeconds() * 1000L);
    }

    /**
     * SOAK: sustained load for soakDurationSeconds. Emits window stats every 30s so you can
     * chart latency drift over time — rising p99 = memory leak or connection pool exhaustion.
     */
    private void runSoak(PerformanceTestRun run, PerformanceTestRequest req) throws Exception {
        log.info("[PerfTest] SOAK runId={} users={} duration={}s",
                run.getId(), req.getVirtualUsers(), req.getSoakDurationSeconds());

        long totalMs = req.getSoakDurationSeconds() * 1000L;
        long windowMs = 30_000L;
        long elapsed = 0;
        AtomicBoolean cancelled = cancelFlags.get(run.getId());

        while (!cancelled.get() && elapsed < totalMs) {
            long windowDuration = Math.min(windowMs, totalMs - elapsed);
            List<PerformanceTestSample> windowSamples =
                    runConcurrentlyCollect(run, req.getVirtualUsers(), windowDuration);

            if (!windowSamples.isEmpty()) {
                Map<String, Object> stats = computeWindowStats(windowSamples);
                stats.put("elapsedSeconds", elapsed / 1000);
                broadcastEvent(run.getId(), "window_stats", stats);
                log.info("[PerfTest] SOAK runId={} elapsed={}s p95={}ms p99={}ms",
                        run.getId(), elapsed / 1000,
                        stats.get("p95LatencyMs"), stats.get("p99LatencyMs"));
            }
            elapsed += windowMs;
        }
    }

    // ── Core concurrent runner ─────────────────────────────────────────────────

    private void runConcurrently(PerformanceTestRun run, int users, long durationMs) throws Exception {
        runConcurrentlyCollect(run, users, durationMs);
    }

    private List<PerformanceTestSample> runConcurrentlyCollect(PerformanceTestRun run,
                                                               int users, long durationMs) throws Exception {
        long deadline = System.currentTimeMillis() + durationMs;
        long testStart = System.currentTimeMillis();
        AtomicBoolean cancelled = cancelFlags.get(run.getId());
        AtomicLong totalRequests = new AtomicLong();
        AtomicLong successCount = new AtomicLong();
        List<PerformanceTestSample> allSamples = new CopyOnWriteArrayList<>();
        List<PerformanceTestSample> pendingInsert = new CopyOnWriteArrayList<>();

        // Parse prerequisite chain once — shared read-only across all threads
        List<PrerequisiteStep> prereqChain = parsePrereqChain(run.getPrerequisiteChainJson());

        ExecutorService pool = Executors.newFixedThreadPool(users);
        List<Future<?>> futures = new ArrayList<>();

        for (int u = 1; u <= users; u++) {
            final int userId = u;
            futures.add(pool.submit(() -> {
                while (System.currentTimeMillis() < deadline && !cancelled.get()) {

                    // Each virtual user runs its own prereq chain to get fresh tokens/session data.
                    // Map of captured values: placeholder name → resolved value
                    Map<String, String> captures = new java.util.LinkedHashMap<>();
                    boolean prereqFailed = false;

                    if (!prereqChain.isEmpty()) {
                        for (PrerequisiteStep prereq : prereqChain) {
                            PrerequisiteResult pr = runPrerequisite(prereq, userId, captures, run.getUserId());
                            if (!pr.success()) {
                                log.warn("[PerfTest] runId={} VU={} prereq '{}' failed: {} — skipping this iteration",
                                        run.getId(), userId, prereq.getName(), pr.errorDetail());
                                prereqFailed = true;
                                break;
                            }
                            // Merge captured values — available to subsequent prereqs and target
                            captures.putAll(pr.captures());
                        }
                    }

                    if (prereqFailed) continue; // don't record a sample, just retry the loop

                    // Fire the target request with captures injected as placeholders
                    PerformanceTestSample sample = fireRequest(run, userId, users, captures);
                    allSamples.add(sample);
                    pendingInsert.add(sample);
                    long total = totalRequests.incrementAndGet();
                    if (sample.getSuccess()) successCount.incrementAndGet();

                    if (pendingInsert.size() >= 100) {
                        List<PerformanceTestSample> batch = new ArrayList<>(pendingInsert);
                        pendingInsert.clear();
                        sampleRepository.saveAll(batch);
                    }

                    if (total % 50 == 0) {
                        double elapsedSec = (System.currentTimeMillis() - testStart) / 1000.0;
                        broadcastEvent(run.getId(), "progress", Map.of(
                                "totalRequests", total,
                                "successRatePct", total > 0 ? successCount.get() * 100.0 / total : 100.0,
                                "rps", elapsedSec > 0 ? total / elapsedSec : 0,
                                "concurrentUsers", users
                        ));
                    }
                }
            }));
        }

        pool.shutdown();
        pool.awaitTermination(durationMs + 15_000L, TimeUnit.MILLISECONDS);
        pool.shutdownNow();

        if (!pendingInsert.isEmpty()) sampleRepository.saveAll(new ArrayList<>(pendingInsert));
        return allSamples;
    }

    // ── Prerequisite execution ────────────────────────────────────────────────

    /**
     * Executes one prerequisite step for a virtual user.
     * Returns captured field values extracted from the response.
     * Latency is NOT recorded as a test sample — prereqs are setup cost, not what we're measuring.
     */
    private PrerequisiteResult runPrerequisite(PrerequisiteStep prereq, int userId,
                                               Map<String, String> previousCaptures, Long ownerUserId) {
        try {
            com.example.perfservice.entity.PerformanceTestApi api =
                    apiRepository.findByIdAndUserId(prereq.getApiId(), ownerUserId)
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "Prerequisite API not found: " + prereq.getApiId()));

            String url    = resolvePlaceholders(api.getUrl(), previousCaptures);
            String method = api.getMethod().toUpperCase();

            Request.Builder rb = new Request.Builder().url(url);

            // Apply headers with placeholder resolution
            if (api.getHeadersJson() != null && !api.getHeadersJson().isBlank()) {
                try {
                    Map<?, ?> headers = objectMapper.readValue(api.getHeadersJson(), Map.class);
                    headers.forEach((k, v) -> rb.header(
                            resolvePlaceholders(String.valueOf(k), previousCaptures),
                            resolvePlaceholders(String.valueOf(v), previousCaptures)));
                } catch (Exception ignored) {}
            }

            // Body — use payloadList round-robin if set, otherwise single body
            String body = api.getBodyJson();
            if (api.getPayloadListJson() != null && !api.getPayloadListJson().isBlank()) {
                try {
                    List<String> payloads = objectMapper.readValue(api.getPayloadListJson(),
                            new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                    if (!payloads.isEmpty()) body = payloads.get((userId - 1) % payloads.size());
                } catch (Exception ignored) {}
            }
            body = body != null ? resolvePlaceholders(body, previousCaptures) : null;

            RequestBody requestBody = null;
            if (body != null && !body.isBlank() && !method.equals("GET")) {
                requestBody = RequestBody.create(body, JSON_MEDIA);
            }
            rb.method(method, requestBody);

            try (Response response = okHttpClient.newCall(rb.build()).execute()) {
                if (!response.isSuccessful()) {
                    String errBody = response.body() != null ? response.body().string() : "";
                    return new PrerequisiteResult(false, Map.of(),
                            "HTTP " + response.code() + ": " + errBody.substring(0, Math.min(200, errBody.length())));
                }

                String responseBody = response.body() != null ? response.body().string() : "";

                // Extract captured fields using Jackson path traversal
                Map<String, String> captures = new java.util.LinkedHashMap<>();
                if (prereq.getCaptures() != null && !responseBody.isBlank()) {
                    com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(responseBody);
                    for (PrerequisiteStep.FieldCapture capture : prereq.getCaptures()) {
                        String value = resolveJsonPath(root, capture.getField());
                        if (value != null) {
                            captures.put(capture.getAs(), value);
                            log.debug("[PerfTest] VU={} prereq='{}' captured {}={}...",
                                    userId, prereq.getName(), capture.getAs(),
                                    value.length() > 20 ? value.substring(0, 20) : value);
                        } else {
                            log.warn("[PerfTest] VU={} prereq='{}' field '{}' not found in response",
                                    userId, prereq.getName(), capture.getField());
                        }
                    }
                }
                return new PrerequisiteResult(true, captures, null);
            }
        } catch (Exception e) {
            return new PrerequisiteResult(false, Map.of(), e.getMessage());
        }
    }

    /** Resolves {{PLACEHOLDER}} patterns in a string using the captures map. */
    private String resolvePlaceholders(String template, Map<String, String> captures) {
        if (template == null || captures.isEmpty()) return template;
        String result = template;
        for (Map.Entry<String, String> entry : captures.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }

    /** Navigates a JSON node by dot-notation path. */
    private String resolveJsonPath(com.fasterxml.jackson.databind.JsonNode root, String path) {
        com.fasterxml.jackson.databind.JsonNode current = root;
        for (String segment : path.split("\\.")) {
            if (current == null || current.isNull()) return null;
            current = current.isObject() ? current.get(segment) : null;
        }
        if (current == null || current.isNull()) return null;
        return current.isValueNode() ? current.asText() : current.toString();
    }

    private record PrerequisiteResult(boolean success, Map<String, String> captures, String errorDetail) {}

    private List<PrerequisiteStep> parsePrereqChain(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<PrerequisiteStep>>() {});
        } catch (Exception e) {
            log.warn("[PerfTest] Could not parse prerequisiteChainJson: {}", e.getMessage());
            return List.of();
        }
    }

    private PerformanceTestSample fireRequest(PerformanceTestRun run, int userId,
                                              int concurrentUsers, Map<String, String> captures) {
        long start = System.currentTimeMillis();
        PerformanceTestSample sample = new PerformanceTestSample();
        sample.setRunId(run.getId());
        sample.setVirtualUserId(userId);
        sample.setFiredAt(LocalDateTime.now());
        sample.setConcurrentUsers(concurrentUsers);

        try {
            // Resolve {{PLACEHOLDER}} in URL using captures from prereq chain
            String resolvedUrl = resolvePlaceholders(run.getResolvedUrl(), captures);
            Request.Builder rb = new Request.Builder().url(resolvedUrl);

            // Apply headers — resolve placeholders (e.g. Authorization: Bearer {{AUTH_TOKEN}})
            if (run.getResolvedHeadersJson() != null && !run.getResolvedHeadersJson().isBlank()) {
                try {
                    Map<?, ?> headers = objectMapper.readValue(run.getResolvedHeadersJson(), Map.class);
                    headers.forEach((k, v) -> rb.header(
                            resolvePlaceholders(String.valueOf(k), captures),
                            resolvePlaceholders(String.valueOf(v), captures)));
                } catch (Exception ignored) {}
            }

            // Body — round-robin payload list, then resolve placeholders
            String method = run.getResolvedMethod().toUpperCase();
            String bodyToSend = run.getResolvedBodyJson();
            if (run.getPayloadListJson() != null && !run.getPayloadListJson().isBlank()) {
                try {
                    List<String> payloads = objectMapper.readValue(run.getPayloadListJson(),
                            new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                    if (!payloads.isEmpty()) bodyToSend = payloads.get((userId - 1) % payloads.size());
                } catch (Exception e) {
                    log.warn("[PerfTest] Failed to parse payloadList for runId={}", run.getId());
                }
            }
            bodyToSend = resolvePlaceholders(bodyToSend, captures);

            RequestBody requestBody = null;
            if (bodyToSend != null && !bodyToSend.isBlank() && !method.equals("GET")) {
                requestBody = RequestBody.create(bodyToSend, JSON_MEDIA);
            }
            rb.method(method, requestBody);

            try (Response response = okHttpClient.newCall(rb.build()).execute()) {
                sample.setLatencyMs(System.currentTimeMillis() - start);
                sample.setStatusCode(response.code());
                boolean success = response.isSuccessful();
                sample.setSuccess(success);
                if (!success) {
                    String errBody = response.body() != null ? response.body().string() : "";
                    sample.setErrorDetail(errBody.length() > 500 ? errBody.substring(0, 500) : errBody);
                }
            }
        } catch (Exception e) {
            sample.setLatencyMs(System.currentTimeMillis() - start);
            sample.setStatusCode(0);
            sample.setSuccess(false);
            sample.setErrorDetail(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return sample;
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    private void finalizeRun(Long runId) {
        try {
            List<Long> latencies = sampleRepository.findLatenciesSorted(runId);
            long total = latencies.size();
            if (total == 0) { updateStatus(runId, "COMPLETED"); return; }

            long successful = sampleRepository.findByRunIdOrderByFiredAt(runId)
                    .stream().filter(PerformanceTestSample::getSuccess).count();

            PerformanceTestRun run = runRepository.findById(runId).orElseThrow();
            long durationMs = java.time.Duration.between(run.getStartedAt(), LocalDateTime.now()).toMillis();

            // Error summary — group failures by status code so the caller knows
            // whether it's 401 (auth/concurrent session), 429 (rate limiting),
            // 503 (backend capacity), timeout (0), etc.
            Map<Integer, Long> errorBreakdown = sampleRepository.findByRunIdOrderByFiredAt(runId)
                    .stream()
                    .filter(s -> !s.getSuccess())
                    .collect(java.util.stream.Collectors.groupingBy(
                            PerformanceTestSample::getStatusCode,
                            java.util.stream.Collectors.counting()));
            if (!errorBreakdown.isEmpty()) {
                try { run.setErrorSummary(objectMapper.writeValueAsString(errorBreakdown)); }
                catch (Exception ignored) {}
            }

            run.setTotalRequests(total);
            run.setSuccessfulRequests(successful);
            run.setFailedRequests(total - successful);
            run.setErrorRatePercent((total - successful) * 100.0 / total);
            run.setAvgLatencyMs(latencies.stream().mapToLong(l -> l).average().orElse(0));
            run.setMinLatencyMs((double) latencies.get(0));
            run.setMaxLatencyMs((double) latencies.get((int) total - 1));
            run.setP50LatencyMs(percentile(latencies, 50));
            run.setP95LatencyMs(percentile(latencies, 95));
            run.setP99LatencyMs(percentile(latencies, 99));
            run.setThroughputRps(durationMs > 0 ? total / (durationMs / 1000.0) : 0);
            run.setStatus("COMPLETED");
            run.setCompletedAt(LocalDateTime.now());
            runRepository.save(run);

            log.info("[PerfTest] runId={} COMPLETED — total={} success={}% p95={}ms p99={}ms rps={}",
                    runId, total,
                    String.format("%.1f", successful * 100.0 / total),
                    run.getP95LatencyMs(), run.getP99LatencyMs(),
                    String.format("%.1f", run.getThroughputRps()));

            broadcastEvent(runId, "completed", Map.of(
                    "totalRequests", total,
                    "successRatePct", successful * 100.0 / total,
                    "avgLatencyMs", run.getAvgLatencyMs(),
                    "p95LatencyMs", run.getP95LatencyMs(),
                    "p99LatencyMs", run.getP99LatencyMs(),
                    "throughputRps", run.getThroughputRps()
            ));
        } catch (Exception e) {
            log.error("[PerfTest] Failed to finalize runId={}: {}", runId, e.getMessage(), e);
            updateStatus(runId, "COMPLETED");
        }
    }

    private Map<String, Object> computeWindowStats(List<PerformanceTestSample> samples) {
        List<Long> latencies = samples.stream().map(PerformanceTestSample::getLatencyMs).sorted().toList();
        long total = latencies.size();
        long successful = samples.stream().filter(PerformanceTestSample::getSuccess).count();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalRequests", total);
        stats.put("successRatePct", total > 0 ? successful * 100.0 / total : 100.0);
        stats.put("avgLatencyMs", latencies.stream().mapToLong(l -> l).average().orElse(0));
        stats.put("p95LatencyMs", percentile(latencies, 95));
        stats.put("p99LatencyMs", percentile(latencies, 99));
        return stats;
    }

    private double percentile(List<Long> sorted, int pct) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    // ── SSE ───────────────────────────────────────────────────────────────────

    private void broadcastEvent(Long runId, String eventName, Object data) {
        List<SseEmitter> list = emitters.get(runId);
        if (list == null || list.isEmpty()) return;
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name(eventName)
                        .data(objectMapper.writeValueAsString(data)));
            } catch (Exception e) {
                dead.add(emitter);
            }
        }
        list.removeAll(dead);
    }

    private void closeEmitters(Long runId) {
        List<SseEmitter> list = emitters.remove(runId);
        if (list != null) list.forEach(e -> { try { e.complete(); } catch (Exception ignored) {} });
    }

    private void removeEmitter(Long runId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(runId);
        if (list != null) list.remove(emitter);
    }

    private void updateStatus(Long runId, String status) {
        runRepository.findById(runId).ifPresent(r -> {
            r.setStatus(status);
            runRepository.save(r);
        });
    }

}