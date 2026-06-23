package com.example.perfservice.config;

import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class HttpClientConfig {

    @Value("${perf.max-total-virtual-users:200}")
    private int maxVirtualUsers;

    /**
     * Shared OkHttpClient for performance tests.
     * maxRequests/maxRequestsPerHost set to match the max concurrent virtual users —
     * if left at OkHttp's defaults (64/5), tests with more users than that will silently
     * queue requests at the client instead of sending them, making latency measurements
     * incorrect (they'd include queue time, not just network time).
     */
    @Bean
    public OkHttpClient okHttpClient() {
        okhttp3.Dispatcher dispatcher = new okhttp3.Dispatcher();
        dispatcher.setMaxRequests(maxVirtualUsers + 50); // headroom
        dispatcher.setMaxRequestsPerHost(maxVirtualUsers + 50);

        return new OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }
}