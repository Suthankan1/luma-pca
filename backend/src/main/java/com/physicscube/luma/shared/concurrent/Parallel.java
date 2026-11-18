package com.physicscube.luma.shared.concurrent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Runs independent database lookups of one request at the same time, on virtual threads. With the database far from the
 * API (each round trip ~100 ms), a request's time becomes its longest chain of queries instead of their sum. Each task
 * runs outside the caller's transaction (its own, if the method it calls is transactional), so only read work belongs here,
 * and every task must take the academy explicitly, never from a thread-bound context.
 */
public final class Parallel {
    private static final ExecutorService THREADS = Executors.newVirtualThreadPerTaskExecutor();

    private Parallel() {
    }

    public static <T> CompletableFuture<T> start(Supplier<T> work) {
        return CompletableFuture.supplyAsync(work, THREADS);
    }

    /** The task's result; a task's own exception (e.g. not found) is rethrown as itself. */
    public static <T> T join(CompletableFuture<T> task) {
        try {
            return task.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }
}
