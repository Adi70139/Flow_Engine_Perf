package com.example.perfservice.dto;

import lombok.Data;
import java.util.Map;

@Data
public class SingleRequestResult {
    private int statusCode;
    private long latencyMs;
    private String responseBody;
    private boolean success;
    private String error;
    // Flattened dot-notation fields extracted from the response body —
    // same format as the main app's poll-fields, ready for use in capture definitions
    private Map<String, String> availableFields;
}