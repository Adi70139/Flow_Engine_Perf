package com.example.perfservice.service;

import com.example.perfservice.dto.PerformanceTestRequest;
import com.example.perfservice.entity.PerformanceTestRun;
import com.example.perfservice.entity.PerformanceTestSample;
import com.example.perfservice.repository.PerformanceTestRunRepository;
import com.example.perfservice.repository.PerformanceTestSampleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PerformanceTestService {

    private final PerformanceTestRunRepository runRepository;
    private final PerformanceTestSampleRepository sampleRepository;
    private final OkHttpClient okHttpClient;
    private final ObjectMapper objectMapper;

    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");
    private static final double STRESS_ERROR_THRESHOLD = 0.05; // 5%

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final Map<Long, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    // -- Public API ------------------------------------------------------------

    public PerformanceTestRun start(PerformanceTestRequest request) {
        PerformanceTestRun run = buildRun(request);
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
        AtomicBoolean flag = cancelFlags.get(runId);
        if (flag == null) throw new IllegalStateException("No active test with runId: " + runId);
        flag.set(true);
        log.info("[PerfTest] Cancel requested for runId={}", runId);
    }

    public PerformanceTestRun getResult(Long runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
    }

    public List<PerformanceTestSample> getSamples(Long runId) {
        return sampleRepository.findByRunIdOrderByFiredAt(runId);
    }

    public List<PerformanceTestRun> getHistory() {
        return runRepository.findAllByOrderByStartedAtDesc();
    }

    public SseEmitter subscribe(Long runId) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> removeEmitter(runId, emitter));
        emitter.onTimeout(() -> removeEmitter(runId, emitter));
        emitter.onError(e -> removeEmitter(runId, emitter));
        runRepository.findById(runId).ifPresent(run -> {
            try {
                emitter.send(SseEmitter.event().name("status").data(run.getStatus()));
            } catch (Exception ignored) {
            }
        });
        return emitter;
    }

    private PerformanceTestRun buildRun(PerformanceTestRequest req) {
        PerformanceTestRun run = new PerformanceTestRun();
        run.setName(req.getName() != null ? req.getName() : req.getMethod() + " " + req.getUrl());
        run.setResolvedUrl(req.getUrl());
        run.setResolvedMethod(req.getMethod().toUpperCase());
        try {
            run.setResolvedHeadersJson(
                    req.getHeaders() != null ? objectMapper.writeValueAsString(req.getHeaders()) : null);
        } catch (Exception e) {
            run.setResolvedHeadersJson(null);
        }
        run.setResolvedBodyJson(req.getBody());
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

    // -- Test types ------------------------------------------------------------

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
     * Stop when error rate > 5%. Key output: stressBreakingPointUsers - the user ceiling.
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
            log.info("[PerfTest] STRESS runId={} -> {} users", run.getId(), currentUsers);
            broadcastEvent(run.getId(), "ramp", Map.of("users", currentUsers));

            List<PerformanceTestSample> levelSamples =
                    runConcurrentlyCollect(run, currentUsers, req.getStressRampIntervalSeconds() * 1000L);

            long total = levelSamples.size();
            long failed = levelSamples.stream().filter(s -> !s.getSuccess()).count();
            double errorRate = total > 0 ? (double) failed / total : 0;

            log.info("[PerfTest] STRESS runId={} users={} errorRate={}%%",
                    run.getId(), currentUsers, String.format("%.1f", errorRate * 100));

            if (errorRate >= STRESS_ERROR_THRESHOLD) {
                breakingPoint = currentUsers;
                log.info("[PerfTest] STRESS breaking point: {} users ({}% error rate)",
                        currentUsers, String.format("%.1f", errorRate * 100));
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
     * SPIKE: warmup at base users -> burst to spikeUsers -> cooldown back to base.
     * Key output: compare p99 during cooldown vs warmup - good API = they converge back.
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
     * chart latency drift over time - rising p99 = memory leak or connection pool exhaustion.
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

    // -- Core concurrent runner ------------------------------------------------

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

        ExecutorService pool = Executors.newFixedThreadPool(users);
        List<Future<?>> futures = new ArrayList<>();

        for (int u = 1; u <= users; u++) {
            final int userId = u;
            futures.add(pool.submit(() -> {
                while (System.currentTimeMillis() < deadline && !cancelled.get()) {
                    PerformanceTestSample sample = fireRequest(run, userId, users);
                    allSamples.add(sample);
                    pendingInsert.add(sample);
                    long total = totalRequests.incrementAndGet();
                    if (sample.getSuccess()) {
                        successCount.incrementAndGet();
                    }

                    // Batch insert every 100 samples
                    if (pendingInsert.size() >= 100) {
                        List<PerformanceTestSample> batch = new ArrayList<>(pendingInsert);
                        pendingInsert.clear();
                        sampleRepository.saveAll(batch);
                    }

                    // Broadcast progress every 50 requests
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

        // Flush remaining samples
        if (!pendingInsert.isEmpty()) {
            sampleRepository.saveAll(new ArrayList<>(pendingInsert));
        }

        return allSamples;
    }

    private PerformanceTestSample fireRequest(PerformanceTestRun run, int userId, int concurrentUsers) {
        long start = System.currentTimeMillis();
        PerformanceTestSample sample = new PerformanceTestSample();
        sample.setRunId(run.getId());
        sample.setVirtualUserId(userId);
        sample.setFiredAt(LocalDateTime.now());
        sample.setConcurrentUsers(concurrentUsers);

        try {
            Request.Builder rb = new Request.Builder().url(run.getResolvedUrl());

            // Apply snapshotted headers
            if (run.getResolvedHeadersJson() != null && !run.getResolvedHeadersJson().isBlank()) {
                try {
                    Map<?, ?> headers = objectMapper.readValue(run.getResolvedHeadersJson(), Map.class);
                    headers.forEach((k, v) -> rb.header(String.valueOf(k), String.valueOf(v)));
                } catch (Exception ignored) {
                }
            }

            // Build body
            String method = run.getResolvedMethod().toUpperCase();
            RequestBody requestBody = null;
            if (run.getResolvedBodyJson() != null
                    && !run.getResolvedBodyJson().isBlank()
                    && !method.equals("GET")) {
                requestBody = RequestBody.create(run.getResolvedBodyJson(), JSON_MEDIA);
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

    // -- Stats -----------------------------------------------------------------

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
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(pct / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    // -- SSE -------------------------------------------------------------------

    private void broadcastEvent(Long runId, String eventName, Object data) {
        List<SseEmitter> list = emitters.get(runId);
        if (list == null || list.isEmpty()) {
            return;
        }
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
        if (list != null) {
            list.forEach(e -> {
                try {
                    e.complete();
                } catch (Exception ignored) {
                }
            });
        }
    }

    private void removeEmitter(Long runId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(runId);
        if (list != null) {
            list.remove(emitter);
        }
    }

    private void updateStatus(Long runId, String status) {
        runRepository.findById(runId).ifPresent(r -> {
            r.setStatus(status);
            runRepository.save(r);
        });
    }
}

