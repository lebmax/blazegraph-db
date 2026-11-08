/*
 * SPDX-License-Identifier: GPL-2.0-only
 */
package com.bigdata.journal.jini.ha;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/** A real RDF stream whose EOF is released after the rejoin checkpoints. */
final class ControlledLoadSource implements Closeable {

    private final HttpServer server;
    private final ExecutorService executor;
    private final long timeoutNanos;
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch finish = new CountDownLatch(1);
    private final CountDownLatch finished = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<IOException> failure = new AtomicReference<IOException>();

    ControlledLoadSource(final long timeoutMillis) throws IOException {
        if (timeoutMillis <= 0)
            throw new IllegalArgumentException("timeoutMillis must be positive");
        timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(final Runnable task) {
                final Thread thread = new Thread(task, "ha-controlled-load");
                thread.setDaemon(true);
                return thread;
            }
        });
        server.setExecutor(executor);
        server.createContext("/rejoin.nt", new HttpHandler() {
            @Override
            public void handle(final HttpExchange exchange) throws IOException {
                stream(exchange);
            }
        });
        server.start();
    }

    String getUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/rejoin.nt";
    }

    void awaitStarted(final long timeout, final TimeUnit unit)
            throws InterruptedException, TimeoutException, IOException {
        if (!started.await(timeout, unit))
            throw new TimeoutException("The server did not request the controlled LOAD source");
        checkFailure();
    }

    void finish() {
        finish.countDown();
    }

    void awaitFinished(final long timeout, final TimeUnit unit)
            throws InterruptedException, TimeoutException, IOException {
        if (!finished.await(timeout, unit))
            throw new TimeoutException("The controlled LOAD source did not finish");
        checkFailure();
    }

    private void checkFailure() throws IOException {
        final IOException ex = failure.get();
        if (ex != null)
            throw ex;
    }

    private void stream(final HttpExchange exchange) {
        try {
            exchange.getResponseHeaders().set("Content-Type", "application/n-triples");
            exchange.sendResponseHeaders(200, 0); // Chunked until finish().
            final long begin = System.nanoTime();
            long next = 0;
            try (OutputStream out = exchange.getResponseBody()) {
                do {
                    final StringBuilder batch = new StringBuilder(2048);
                    for (int i = 0; i < 32; i++, next++) {
                        batch.append("<urn:ha:rejoin:").append(next)
                                .append("> <urn:ha:loaded> \"").append(next)
                                .append("\" .\n");
                    }
                    out.write(batch.toString().getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    started.countDown();
                    if (System.nanoTime() - begin >= timeoutNanos)
                        throw new IOException("Controlled LOAD was not released before its deadline");
                    // Bound the data volume while continuing real in-flight writes.
                } while (!finish.await(20, TimeUnit.MILLISECONDS));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            recordFailure(new IOException("Controlled LOAD source interrupted", ex));
        } catch (IOException ex) {
            recordFailure(ex);
        } catch (RuntimeException ex) {
            recordFailure(new IOException("Controlled LOAD source failed", ex));
        } finally {
            exchange.close();
            started.countDown();
            finished.countDown();
        }
    }

    private void recordFailure(final IOException ex) {
        if (!closed.get())
            failure.compareAndSet(null, ex);
    }

    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            finish.countDown();
            server.stop(0);
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS))
                    throw new IOException("Controlled LOAD source thread did not stop");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                final InterruptedIOException failure = new InterruptedIOException(
                        "Interrupted while closing controlled LOAD source");
                failure.initCause(ex);
                throw failure;
            }
        }
    }
}
