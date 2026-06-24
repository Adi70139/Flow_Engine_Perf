package com.example.perfservice.controller;

import com.example.perfservice.dto.PerformanceTestApiRequest;
import com.example.perfservice.entity.PerformanceTestApi;
import com.example.perfservice.service.PerformanceTestApiService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/performance/apis")
@Tag(name = "Performance Test APIs", description = "Manage saved API configurations for performance testing")
public class PerformanceTestApiController {

    private final PerformanceTestApiService apiService;

    /**
     * Save an API config for repeated testing.
     * POST /performance/apis
     *
     * With single payload (all users send same body):
     * { "name": "Login", "url": "https://...", "method": "POST",
     *   "headers": {"Content-Type": "application/json"},
     *   "body": "{\"username\":\"user1\",\"password\":\"pass1\"}" }
     *
     * With payload list (round-robin across virtual users):
     * { "name": "Login multi-user", "url": "https://...", "method": "POST",
     *   "headers": {"Content-Type": "application/json"},
     *   "payloadList": [
     *     "{\"username\":\"user1\",\"password\":\"pass1\"}",
     *     "{\"username\":\"user2\",\"password\":\"pass2\"}",
     *     "{\"username\":\"user3\",\"password\":\"pass3\"}"
     *   ] }
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Save an API config",
            description = "Use payloadList for endpoints where each virtual user needs different data " +
                    "(e.g. login with different credentials). Virtual user N gets payloadList[N % size].")
    public PerformanceTestApi create(@Valid @RequestBody PerformanceTestApiRequest request) {
        return apiService.create(request);
    }

    /**
     * List all saved APIs for the current user.
     * GET /performance/apis
     */
    @GetMapping
    @Operation(summary = "List your saved API configs")
    public List<PerformanceTestApi> list() {
        return apiService.listForCurrentUser();
    }

    /**
     * Get a single saved API.
     * GET /performance/apis/{id}
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get a saved API config")
    public PerformanceTestApi getById(@PathVariable Long id) {
        return apiService.getById(id);
    }

    /**
     * Update a saved API.
     * PUT /performance/apis/{id}
     */
    @PutMapping("/{id}")
    @Operation(summary = "Update a saved API config")
    public PerformanceTestApi update(@PathVariable Long id,
                                     @Valid @RequestBody PerformanceTestApiRequest request) {
        return apiService.update(id, request);
    }

    /**
     * Delete a saved API.
     * DELETE /performance/apis/{id}
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a saved API config")
    public void delete(@PathVariable Long id) {
        apiService.delete(id);
    }
}