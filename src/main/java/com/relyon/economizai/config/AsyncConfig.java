package com.relyon.economizai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Enables {@code @Async} and provides the executor for receipt ingestion.
 *
 * <p>Receipt submit returns immediately with status PROCESSING; the slow part
 * (SEFAZ fetch + captcha solve + parse — up to a couple of minutes) runs on
 * this pool so the HTTP request never blocks the FE. The pool is small and
 * bounded: ingestion is I/O-bound on SEFAZ/CapSolver, not CPU, and we'd rather
 * queue a burst than spawn unbounded threads.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String RECEIPT_INGEST_EXECUTOR = "receiptIngestExecutor";

    @Bean(RECEIPT_INGEST_EXECUTOR)
    @Profile("!test")
    public Executor receiptIngestExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("receipt-ingest-");
        executor.initialize();
        return executor;
    }

    /**
     * Under the {@code test} profile run ingestion inline on the caller's thread
     * (and transaction), so {@code @SpringBootTest} flows are deterministic and
     * see the persisted result without cross-thread/commit timing.
     */
    @Bean(RECEIPT_INGEST_EXECUTOR)
    @Profile("test")
    public Executor receiptIngestExecutorSync() {
        return new SyncTaskExecutor();
    }
}
