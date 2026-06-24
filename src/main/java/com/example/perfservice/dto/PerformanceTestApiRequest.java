package com.example.perfservice.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.List;

@Data
public class PerformanceTestApiRequest {

    @NotBlank
    private String name;

    private String description;

    @NotBlank
    private String url;

    @NotBlank
    private String method;

    private java.util.Map<String, String> headers;

    // Single body — used when all virtual users send the same payload.
    // Ignored if payloadList is provided.
    private String body;

    // Multiple bodies — cycled round-robin across virtual users.
    // Virtual user N gets payloadList[N % payloadList.size()].
    // Use this for endpoints where each user needs different data
    // (e.g. login with different credentials per user).
    private List<String> payloadList;
}