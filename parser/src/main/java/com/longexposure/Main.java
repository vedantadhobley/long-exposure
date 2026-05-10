package com.longexposure;

/**
 * Entry point for the parser/worker process.
 *
 * Day 1 stub. Once Temporal SDK plumbing lands, this becomes the worker
 * registration: connect to TEMPORAL_HOST, register the activity classes
 * (DownloadHistActivity, ParseTopsActivity, ScoreEventsActivity, etc.),
 * and block on the worker.
 */
public final class Main {
    private Main() {}

    public static void main(final String[] args) {
        System.out.println("long-exposure-parser starting (stub)");
        System.out.println("TEMPORAL_HOST=" + System.getenv("TEMPORAL_HOST"));
        System.out.println("POSTGRES_HOST=" + System.getenv("POSTGRES_HOST"));
        System.out.println("LLAMA_URL=" + System.getenv("LLAMA_URL"));
        // Keep the container alive so docker compose doesn't restart-loop.
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
