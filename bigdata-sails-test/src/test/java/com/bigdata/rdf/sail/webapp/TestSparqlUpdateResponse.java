/*
 * SPDX-License-Identifier: GPL-2.0-only
 */
package com.bigdata.rdf.sail.webapp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import junit.framework.TestCase2;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;

import com.bigdata.rdf.sail.webapp.client.HttpException;
import com.bigdata.rdf.sail.webapp.client.IPreparedSparqlUpdate;
import com.bigdata.rdf.sail.webapp.client.RemoteRepository;
import com.bigdata.rdf.sail.webapp.client.RemoteRepositoryManager;

/**
 * Regression tests for response cleanup after SPARQL UPDATE requests.
 *
 * The local handler does not parse SPARQL or start a query engine. It only
 * accepts the public RemoteRepository client request and returns a response
 * body, which the update client is expected to release before reusing the
 * destination connection.
 */
public class TestSparqlUpdateResponse extends TestCase2 {

    private static final int QUERY_TIMEOUT_MILLIS = 2000;

    private static final String UPDATE =
            "INSERT DATA { <urn:test:s> <urn:test:p> <urn:test:o> }";

    private static final byte[] RESPONSE_BODY =
            "non-empty update response body".getBytes(StandardCharsets.UTF_8);

    public void testSuccessfulUpdateResponsesReleaseDestinationConnection()
            throws Exception {
        assertTwoUpdatesReachServer(false);
    }

    public void testErrorResponseReleasesDestinationConnection()
            throws Exception {
        assertTwoUpdatesReachServer(true);
    }

    private void assertTwoUpdatesReachServer(final boolean failFirst)
            throws Exception {

        final UpdateHandler handler = new UpdateHandler(failFirst);
        final Server server = new Server();
        final ServerConnector connector = new ServerConnector(server);
        connector.setHost("127.0.0.1");
        connector.setPort(0);
        server.addConnector(connector);
        server.setHandler(handler);

        final HttpClient httpClient =
                new HttpClient();
        RemoteRepositoryManager manager = null;

        try {
            server.start();

            httpClient.setMaxConnectionsPerDestination(1);
            httpClient.start();

            final String serviceURL =
                    "http://127.0.0.1:" + connector.getLocalPort();
            manager = new RemoteRepositoryManager(serviceURL, false,
                    httpClient, null);

            final RemoteRepository repository =
                    manager.getRepositoryForNamespace("test");

            if (failFirst) {
                try {
                    executeUpdate(repository);
                    fail("Expected the first update to receive HTTP 500");
                } catch (HttpException ex) {
                    assertEquals("Unexpected status for the first update",
                            HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                            ex.getStatusCode());
                }
            } else {
                executeUpdate(repository);
            }

            executeUpdate(repository);

            assertEquals("Both update requests should reach the local server",
                    2, handler.getUpdateCount());
        } finally {
            try {
                if (manager != null) {
                    manager.close();
                }
            } finally {
                try {
                    if (httpClient.isStarted()) {
                        httpClient.stop();
                    }
                } finally {
                    if (server.isRunning()) {
                        server.stop();
                    }
                }
            }
        }
    }

    private void executeUpdate(final RemoteRepository repository)
            throws Exception {
        final IPreparedSparqlUpdate update = repository.prepareUpdate(UPDATE);
        update.setMaxQueryMillis(QUERY_TIMEOUT_MILLIS);
        update.evaluate();
    }

    private static final class UpdateHandler extends AbstractHandler {

        private final boolean failFirst;
        private final AtomicInteger updateCount = new AtomicInteger();

        private UpdateHandler(final boolean failFirst) {
            this.failFirst = failFirst;
        }

        @Override
        public void handle(final String target, final Request baseRequest,
                final HttpServletRequest request,
                final HttpServletResponse response)
                throws IOException, ServletException {

            if (!"POST".equals(request.getMethod())
                    || !"/namespace/test/sparql".equals(request.getRequestURI())) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                baseRequest.setHandled(true);
                return;
            }

            consumeRequestBody(request);

            final int requestNumber = updateCount.incrementAndGet();
            final int status = failFirst && requestNumber == 1
                    ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                    : HttpServletResponse.SC_OK;

            baseRequest.setHandled(true);
            response.setStatus(status);
            response.setContentType("text/plain; charset=UTF-8");
            response.setContentLength(RESPONSE_BODY.length);
            response.getOutputStream().write(RESPONSE_BODY);
        }

        private static void consumeRequestBody(final HttpServletRequest request)
                throws IOException {
            final InputStream input = request.getInputStream();
            final byte[] buffer = new byte[1024];

            while (input.read(buffer) != -1) {
                // Drain the request without parsing the SPARQL update.
            }
        }

        private int getUpdateCount() {
            return updateCount.get();
        }
    }
}
