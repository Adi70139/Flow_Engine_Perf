package com.example.perfservice.dto;

import com.example.perfservice.entity.PerformanceTestRun;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Rates a completed test run. Thresholds are intentionally conservative defaults —
 * real acceptable thresholds depend on the specific API's SLA. Override via config if needed.
 *
 * Rating scale:
 *   EXCELLENT  — no meaningful issues
 *   GOOD       — acceptable for most use cases, minor concerns
 *   NEEDS_WORK — degraded in at least one important dimension
 *   POOR       — multiple significant issues
 *   INVALID    — error rate too high to draw performance conclusions
 */
@Data
public class PerformanceRating {

    private String rating;         // EXCELLENT | GOOD | NEEDS_WORK | POOR | INVALID
    private String summary;        // one-line verdict
    private List<String> issues = new ArrayList<>();
    private List<String> strengths = new ArrayList<>();
    private String errorSummary; // JSON: {statusCode: count} e.g. {"401": 250, "0": 9}

    // Raw numbers echoed back for convenience
    private double errorRatePct;
    private double p95LatencyMs;
    private double p99LatencyMs;
    private double throughputRps;
    private long totalRequests;

    public static PerformanceRating evaluate(PerformanceTestRun run) {
        PerformanceRating r = new PerformanceRating();
        r.errorRatePct   = run.getErrorRatePercent() != null ? run.getErrorRatePercent() : 0;
        r.p95LatencyMs   = run.getP95LatencyMs()     != null ? run.getP95LatencyMs()     : 0;
        r.p99LatencyMs   = run.getP99LatencyMs()     != null ? run.getP99LatencyMs()     : 0;
        r.throughputRps  = run.getThroughputRps()    != null ? run.getThroughputRps()    : 0;
        r.totalRequests  = run.getTotalRequests()    != null ? run.getTotalRequests()     : 0;

        // Error rate above 5% means the performance numbers are unreliable —
        // latency of failed requests (especially fast-rejected ones) skews the distribution.
        // Don't rate performance on a broken API; fix errors first.
        if (r.errorRatePct > 5.0) {
            r.rating = "INVALID";
            r.summary = String.format(
                    "%.1f%% of requests failed — fix errors before drawing performance conclusions. " +
                            "Check errorSummary for the status code breakdown.", r.errorRatePct);
            r.issues.add(String.format("%.1f%% error rate (acceptable threshold: <5%%)", r.errorRatePct));
            return r;
        }

        // Score each dimension independently, then combine
        int score = 0; // 0 = best, increases with problems

        // p95 latency — the number that matters most for user experience
        // Most web API SLAs target p95 < 500ms; aggressive targets are < 200ms
        if (r.p95LatencyMs < 200) {
            r.strengths.add(String.format("p95 latency %.0fms — fast (under 200ms target)", r.p95LatencyMs));
        } else if (r.p95LatencyMs < 500) {
            r.strengths.add(String.format("p95 latency %.0fms — acceptable (under 500ms)", r.p95LatencyMs));
            score += 1;
        } else if (r.p95LatencyMs < 1000) {
            r.issues.add(String.format("p95 latency %.0fms — degraded (target: <500ms)", r.p95LatencyMs));
            score += 2;
        } else {
            r.issues.add(String.format("p95 latency %.0fms — very high (target: <500ms)", r.p95LatencyMs));
            score += 3;
        }

        // p99 tail latency — indicates outliers / GC pauses / connection pool saturation
        double tailRatio = r.p95LatencyMs > 0 ? r.p99LatencyMs / r.p95LatencyMs : 1;
        if (tailRatio > 3.0) {
            r.issues.add(String.format(
                    "p99/p95 ratio %.1fx — high tail latency suggests GC pauses, connection pool " +
                            "exhaustion, or lock contention at peak load", tailRatio));
            score += 2;
        } else if (tailRatio > 2.0) {
            r.issues.add(String.format("p99/p95 ratio %.1fx — some tail latency variance worth watching", tailRatio));
            score += 1;
        } else {
            r.strengths.add(String.format("p99/p95 ratio %.1fx — consistent latency distribution", tailRatio));
        }

        // Throughput
        if (r.throughputRps >= 50) {
            r.strengths.add(String.format("%.1f req/s throughput", r.throughputRps));
        } else if (r.throughputRps >= 20) {
            r.strengths.add(String.format("%.1f req/s throughput — moderate", r.throughputRps));
        } else {
            r.issues.add(String.format("%.1f req/s throughput — low for a typical API endpoint", r.throughputRps));
            score += 1;
        }

        // Error rate in the acceptable range (0–5%) — note if non-zero
        if (r.errorRatePct > 1.0) {
            r.issues.add(String.format("%.1f%% error rate — low but non-zero; investigate before load increases", r.errorRatePct));
            score += 1;
        } else if (r.errorRatePct == 0) {
            r.strengths.add("0% error rate");
        }

        if (score == 0) {
            r.rating = "EXCELLENT";
            r.summary = "API is performing well under this load — no meaningful issues detected.";
        } else if (score <= 2) {
            r.rating = "GOOD";
            r.summary = "API is performing acceptably — minor issues worth monitoring but not urgent.";
        } else if (score <= 4) {
            r.rating = "NEEDS_WORK";
            r.summary = "API shows degradation under this load — investigate before increasing traffic.";
        } else {
            r.rating = "POOR";
            r.summary = "API is struggling under this load — multiple significant issues detected.";
        }

        r.errorSummary = run.getErrorSummary();
        return r;
    }
}