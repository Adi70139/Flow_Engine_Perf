package com.example.perfservice.controller;

import com.example.perfservice.dto.PerformanceRating;
import com.example.perfservice.dto.PerformanceTestRequest;
import com.example.perfservice.entity.PerformanceTestRun;
import com.example.perfservice.entity.PerformanceTestSample;
import com.example.perfservice.service.PerformanceTestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/performance")
@Tag(name = "Performance Testing")
public class PerformanceTestController {

    private final PerformanceTestService performanceTestService;

    @PostMapping("/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Start performance test",
            description = "LOAD | STRESS | SPIKE | SOAK. Returns runId immediately — " +
                    "subscribe to /{runId}/stream for real-time SSE progress.")
    public PerformanceTestRun start(@Valid @RequestBody PerformanceTestRequest request) {
        return performanceTestService.start(request);
    }

    @GetMapping(value = "/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "SSE progress stream",
            description = "Events: status | progress | ramp | phase | breaking_point | window_stats | completed | error")
    public SseEmitter stream(@PathVariable Long runId) {
        return performanceTestService.subscribe(runId);
    }

    @GetMapping("/{runId}")
    @Operation(summary = "Get result / current status")
    public PerformanceTestRun getResult(@PathVariable Long runId) {
        return performanceTestService.getResult(runId);
    }

    @GetMapping("/{runId}/rate")
    @Operation(summary = "Rate test results",
            description = "Returns EXCELLENT/GOOD/NEEDS_WORK/POOR/INVALID with specific reasoning. " +
                    "INVALID means error rate is too high to draw performance conclusions — fix errors first.")
    public PerformanceRating rate(@PathVariable Long runId) {
        PerformanceTestRun run = performanceTestService.getResult(runId);
        if (!"COMPLETED".equals(run.getStatus())) {
            throw new IllegalStateException("Test is not completed yet — status: " + run.getStatus());
        }
        return PerformanceRating.evaluate(run);
    }

    @GetMapping("/{runId}/samples")
    @Operation(summary = "Per-request latency samples — use for timeline chart")
    public List<PerformanceTestSample> getSamples(@PathVariable Long runId) {
        return performanceTestService.getSamples(runId);
    }

    @GetMapping("/history")
    @Operation(summary = "All test runs — newest first")
    public List<PerformanceTestRun> getHistory() {
        return performanceTestService.getHistory();
    }

    @PostMapping("/{runId}/cancel")
    @Operation(summary = "Cancel a running test — already-collected samples are preserved")
    public void cancel(@PathVariable Long runId) {
        performanceTestService.cancel(runId);
    }
}