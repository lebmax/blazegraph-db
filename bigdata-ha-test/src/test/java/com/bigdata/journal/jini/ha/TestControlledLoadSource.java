/*
 * SPDX-License-Identifier: GPL-2.0-only
 */
package com.bigdata.journal.jini.ha;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;

import org.openrdf.model.Model;
import org.openrdf.model.impl.ValueFactoryImpl;
import org.openrdf.rio.RDFFormat;
import org.openrdf.rio.Rio;

/** Tests the real HTTP and RDF behavior of {@link ControlledLoadSource}. */
public class TestControlledLoadSource extends TestCase {

    private static final long CONNECT_TIMEOUT_MILLIS = 5000L;
    private static final long READ_TIMEOUT_MILLIS = 10000L;

    public void testRdfResponseRemainsOpenUntilExplicitFinish() throws Exception {
        final ExecutorService readerExecutor = Executors.newSingleThreadExecutor();
        try (ControlledLoadSource source = new ControlledLoadSource(30000L)) {
            final URL url = new URL(source.getUrl());
            final CountDownLatch firstBytesRead = new CountDownLatch(1);
            final FutureTask<ResponseBody> reader = new FutureTask<ResponseBody>(
                    new Callable<ResponseBody>() {
                        @Override
                        public ResponseBody call() throws Exception {
                            final HttpURLConnection connection = openConnection(url);
                            try {
                                final int responseCode = connection.getResponseCode();
                                final ByteArrayOutputStream body = new ByteArrayOutputStream();
                                try (InputStream input = connection.getInputStream()) {
                                    final byte[] buffer = new byte[4096];
                                    int count;
                                    while ((count = input.read(buffer)) != -1) {
                                        if (count > 0) {
                                            firstBytesRead.countDown();
                                            body.write(buffer, 0, count);
                                        }
                                    }
                                }
                                return new ResponseBody(responseCode, body.toByteArray());
                            } finally {
                                connection.disconnect();
                            }
                        }
                    });

            readerExecutor.execute(reader);
            source.awaitStarted(5L, TimeUnit.SECONDS);
            assertTrue("The HTTP reader must receive RDF before finish",
                    firstBytesRead.await(5L, TimeUnit.SECONDS));
            assertFalse("The chunked RDF response must remain open before finish",
                    reader.isDone());

            source.finish();
            final ResponseBody response = reader.get(10L, TimeUnit.SECONDS);
            source.awaitFinished(5L, TimeUnit.SECONDS);

            assertEquals(200, response.statusCode);
            final Model model = Rio.parse(new ByteArrayInputStream(response.body),
                    url.toExternalForm(), RDFFormat.NTRIPLES);
            final ValueFactoryImpl valueFactory = ValueFactoryImpl.getInstance();
            assertTrue("The complete HTTP body must parse and include its first literal triple",
                    model.contains(valueFactory.createURI("urn:ha:rejoin:0"),
                            valueFactory.createURI("urn:ha:loaded"),
                            valueFactory.createLiteral("0")));
        } finally {
            shutdownReaderExecutor(readerExecutor);
        }
    }

    public void testDeadlineIsReportedAfterHttpOkResponse() throws Exception {
        final ExecutorService readerExecutor = Executors.newSingleThreadExecutor();
        try (ControlledLoadSource source = new ControlledLoadSource(3000L)) {
            final URL url = new URL(source.getUrl());
            final HttpURLConnection connection = openConnection(url);
            try {
                assertEquals("The streaming response headers should already be successful",
                        200, connection.getResponseCode());

                final InputStream responseBody = connection.getInputStream();
                final FutureTask<Void> reader = new FutureTask<Void>(new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        try (InputStream input = responseBody) {
                            final byte[] buffer = new byte[4096];
                            while (input.read(buffer) != -1) {
                                // Drain the actual response; deadline closure may end it with I/O error.
                            }
                        } catch (IOException expectedWhenDeadlineClosesStream) {
                            // The producer reports the deadline through awaitFinished below.
                        }
                        return null;
                    }
                });
                readerExecutor.execute(reader);

                source.awaitStarted(5L, TimeUnit.SECONDS);
                IOException deadlineFailure = null;
                try {
                    source.awaitFinished(10L, TimeUnit.SECONDS);
                    fail("The unreleased source must report its deadline");
                } catch (IOException expected) {
                    deadlineFailure = expected;
                }
                assertNotNull(deadlineFailure);
                assertTrue("The IOException should identify the source deadline",
                        deadlineFailure.getMessage() != null
                                && deadlineFailure.getMessage().toLowerCase().contains("deadline"));
                reader.get(10L, TimeUnit.SECONDS);
            } finally {
                connection.disconnect();
            }
        } finally {
            shutdownReaderExecutor(readerExecutor);
        }
    }

    public void testCloseReleasesReaderAndUnbindsHttpPort() throws Exception {
        final ExecutorService readerExecutor = Executors.newSingleThreadExecutor();
        try (ControlledLoadSource source = new ControlledLoadSource(30000L)) {
            final URL url = new URL(source.getUrl());
            final HttpURLConnection connection = openConnection(url);
            try {
                assertEquals(200, connection.getResponseCode());
                final InputStream responseBody = connection.getInputStream();
                final CountDownLatch firstBytesRead = new CountDownLatch(1);
                final FutureTask<Void> reader = new FutureTask<Void>(new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        try (InputStream input = responseBody) {
                            final byte[] buffer = new byte[4096];
                            int count;
                            while ((count = input.read(buffer)) != -1) {
                                if (count > 0)
                                    firstBytesRead.countDown();
                            }
                        } catch (IOException expectedWhenSourceCloses) {
                            // Closing the source may end an in-flight response with an I/O error.
                        }
                        return null;
                    }
                });
                readerExecutor.execute(reader);

                source.awaitStarted(5L, TimeUnit.SECONDS);
                assertTrue("The reader must be consuming the live response",
                        firstBytesRead.await(5L, TimeUnit.SECONDS));
                assertFalse("The reader should still be waiting for response EOF", reader.isDone());

                final int port = url.getPort();
                source.close();
                reader.get(5L, TimeUnit.SECONDS);
                connection.disconnect();
                assertPortIsUnbound(port);
            } finally {
                connection.disconnect();
            }
        } finally {
            shutdownReaderExecutor(readerExecutor);
        }
    }

    private static HttpURLConnection openConnection(final URL url) throws IOException {
        final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout((int) CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout((int) READ_TIMEOUT_MILLIS);
        connection.setUseCaches(false);
        return connection;
    }

    private static void assertPortIsUnbound(final int port) throws IOException {
        final Socket probe = new Socket();
        try {
            try {
                probe.connect(new InetSocketAddress("127.0.0.1", port),
                        (int) CONNECT_TIMEOUT_MILLIS);
                fail("The HTTP port must reject new connections after close");
            } catch (ConnectException expected) {
                // The listener has been removed.
            }
        } finally {
            probe.close();
        }
    }

    private static void shutdownReaderExecutor(final ExecutorService executor)
            throws InterruptedException {
        executor.shutdownNow();
        assertTrue("The HTTP reader executor must terminate",
                executor.awaitTermination(5L, TimeUnit.SECONDS));
    }

    private static final class ResponseBody {
        private final int statusCode;
        private final byte[] body;

        private ResponseBody(final int statusCode, final byte[] body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }
}
