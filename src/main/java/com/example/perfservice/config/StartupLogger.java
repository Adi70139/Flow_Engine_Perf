package com.example.perfservice.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Component
public class StartupLogger {
    private static final Logger logger = LoggerFactory.getLogger(StartupLogger.class);

    private final DataSource dataSource;

    public StartupLogger(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            String url = dataSource.getConnection().getMetaData().getURL();
            String dbName = dataSource.getConnection().getMetaData().getDatabaseProductName();
            String dbVersion = dataSource.getConnection().getMetaData().getDatabaseProductVersion();

            logger.info("========================================");
            logger.info("✓ Database Connection Established");
            logger.info("  Database URL: {}", url);
            logger.info("  Database Type: {} (Version: {})", dbName, dbVersion);
            logger.info("========================================");
        } catch (Exception e) {
            logger.error("Failed to log database connection info", e);
        }
    }
}

