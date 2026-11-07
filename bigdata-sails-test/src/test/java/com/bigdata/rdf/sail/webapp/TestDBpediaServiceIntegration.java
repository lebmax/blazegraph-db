/*
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 */
package com.bigdata.rdf.sail.webapp;

import java.util.HashSet;
import java.util.Set;

import junit.extensions.TestSetup;
import junit.extensions.proxy.ProxyTestSuite;
import junit.framework.Test;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.runner.RunWith;
import org.junit.runners.AllTests;
import org.openrdf.model.Literal;
import org.openrdf.model.Value;
import org.openrdf.model.impl.URIImpl;
import org.openrdf.query.BindingSet;
import org.openrdf.query.TupleQueryResult;
import org.openrdf.query.resultio.TupleQueryResultFormat;

import com.bigdata.bop.fed.QueryEngineFactory;
import com.bigdata.journal.BufferMode;
import com.bigdata.journal.IIndexManager;
import com.bigdata.rdf.sail.webapp.client.IPreparedTupleQuery;
import com.bigdata.rdf.sail.webapp.client.RemoteRepository;
import com.bigdata.rdf.sail.webapp.client.RemoteRepositoryManager;
import com.bigdata.rdf.sparql.ast.service.RemoteServiceFactoryImpl;
import com.bigdata.rdf.sparql.ast.service.RemoteServiceOptions;
import com.bigdata.rdf.sparql.ast.service.ServiceRegistry;

/**
 * Opt-in integration tests against the public DBpedia endpoint. These tests are
 * deliberately absent from TestAll: network, TLS and dataset failures must be
 * reported as failures when explicitly requested, without affecting offline tests.
 *
 * <pre>
 * mvn -pl bigdata-sails-test -am -Dtest=TestDBpediaServiceIntegration -Dsurefire.failIfNoSpecifiedTests=false verify
 * </pre>
 *
 * The endpoint uses HTTPS; DBpedia resource and ontology IRIs still use HTTP.
 * Request JSON explicitly: some DBpedia XML responses include both xml:lang and
 * rdf:langString, for which the legacy Sesame XML parser drops the language tag.
 * See https://www.dbpedia.org/resources/sparql/ .
 */
@RunWith(AllTests.class)
public class TestDBpediaServiceIntegration<S extends IIndexManager> extends
        AbstractTestNanoSparqlClient<S> {

    private static final String ENDPOINT = "https://dbpedia.org/sparql";
    private static final String SOURCE =
            "http://dbpedia.org/resource/Tonga_(Nyasa)_language";
    private static final String RESOURCE =
            "http://dbpedia.org/resource/Tonga_language_(Malawi)";
    private static final long CONNECT_TIMEOUT_MILLIS = 10000L;
    private static final long QUERY_TIMEOUT_MILLIS = 30000L;

    // The old Tonga IRI now redirects in the RDF dataset, rather than carrying
    // labels itself. This is an RDF property path, not an HTTP redirect.
    private static final String LABEL_PATTERN =
            "?source <http://dbpedia.org/ontology/wikiPageRedirects>* ?resource . "
            + "?resource <http://www.w3.org/2000/01/rdf-schema#label> ?label . "
            + "FILTER(LANG(?label) = \"en\") ";

    public TestDBpediaServiceIntegration() {
    }

    public TestDBpediaServiceIntegration(final String name) {
        super(name);
    }

    public static Test suite() {
        final ProxyTestSuite suite = TestNanoSparqlServerWithProxyIndexManager
                .createProxyTestSuite(TestNanoSparqlServerWithProxyIndexManager
                        .getTemporaryJournal(BufferMode.Transient), TestMode.quads);
        suite.addTestSuite(TestDBpediaServiceIntegration.class);
        return new TestSetup(suite) {
            @Override
            protected void tearDown() throws Exception {
                ((TestNanoSparqlServerWithProxyIndexManager<?>) suite.getDelegate())
                        .tearDownAfterSuite();
            }
        };
    }

    /** Verify the client, result parser and the JDK trust store independently. */
    public void testHttpsTupleQuery() throws Exception {
        final String query = "SELECT ?label WHERE { "
                + "<http://dbpedia.org/resource/Berlin> "
                + "<http://www.w3.org/2000/01/rdf-schema#label> ?label . "
                + "FILTER(LANG(?label) = \"en\") } LIMIT 10";
        final Set<Value> labels = queryHttps(query, false);
        assertEquals("English Berlin label", 1, labels.size());
        assertEquals("Berlin", ((Literal) labels.iterator().next()).getLabel());
    }

    public void testRedirectedResourceLabels() throws Exception {
        final Set<Value> labels = queryHttps(
                "SELECT ?source ?resource ?label WHERE { " + sourceBinding()
                        + LABEL_PATTERN + "} LIMIT 10", true);
        assertFalse("No English labels for " + SOURCE, labels.isEmpty());
    }

    /** Verify that an outer VALUES binding reaches the live HTTPS SERVICE. */
    public void testServiceNodeBindings() throws Exception {
        final HttpClient serviceClient = QueryEngineFactory.getInstance()
                .getQueryController(getIndexManager()).getClientConnectionManager();
        final long oldConnectTimeout = serviceClient.getConnectTimeout();
        final long oldIdleTimeout = serviceClient.getIdleTimeout();
        m_client.setIdleTimeout(QUERY_TIMEOUT_MILLIS);

        final URIImpl serviceURI = new URIImpl(ENDPOINT);
        final ServiceRegistry registry = ServiceRegistry.getInstance();
        assertNull("The opt-in test needs an unconfigured DBpedia endpoint",
                registry.get(serviceURI));
        final RemoteServiceOptions options = new RemoteServiceOptions();
        options.setAcceptHeader(TupleQueryResultFormat.JSON.getDefaultMIMEType());
        registry.add(serviceURI, new RemoteServiceFactoryImpl(options));
        try {
            serviceClient.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            serviceClient.setIdleTimeout(QUERY_TIMEOUT_MILLIS);
            final String query = "SELECT ?source ?resource ?label WHERE { "
                    + sourceBinding() + "SERVICE <" + ENDPOINT + "> { "
                    + LABEL_PATTERN + "} } LIMIT 10";
            final Set<Value> labels = readLabels(m_repo, query, true);
            assertFalse("No English labels through SERVICE for " + SOURCE,
                    labels.isEmpty());
        } finally {
            registry.remove(serviceURI);
            serviceClient.setConnectTimeout(oldConnectTimeout);
            serviceClient.setIdleTimeout(oldIdleTimeout);
        }
    }

    private static String sourceBinding() {
        return "VALUES ?source { <" + SOURCE + "> } ";
    }

    private Set<Value> queryHttps(final String query,
            final boolean checkResource) throws Exception {
        // Use normal certificate and hostname verification for the direct probe.
        final SslContextFactory ssl = new SslContextFactory();
        ssl.setEndpointIdentificationAlgorithm("HTTPS");
        final HttpClient client = new HttpClient(ssl);
        client.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        client.setIdleTimeout(QUERY_TIMEOUT_MILLIS);
        client.setFollowRedirects(false);
        client.start();
        try {
            final RemoteRepositoryManager manager = new RemoteRepositoryManager(
                    ENDPOINT, false, client, getIndexManager().getExecutorService());
            try {
                return readLabels(manager.getRepositoryForURL(ENDPOINT), query,
                        checkResource);
            } finally {
                manager.close();
            }
        } finally {
            client.stop();
        }
    }

    private Set<Value> readLabels(final RemoteRepository repository,
            final String query, final boolean checkResource) throws Exception {
        final IPreparedTupleQuery prepared = repository.prepareTupleQuery(query);
        prepared.setMaxQueryMillis(QUERY_TIMEOUT_MILLIS);
        prepared.setAcceptHeader(TupleQueryResultFormat.JSON.getDefaultMIMEType());
        final Set<Value> labels = new HashSet<Value>();
        final TupleQueryResult result = prepared.evaluate();
        try {
            while (result.hasNext()) {
                final BindingSet row = result.next();
                if (checkResource) {
                    assertNotNull("Missing source binding: " + row, row.getValue("source"));
                    assertEquals(SOURCE, row.getValue("source").stringValue());
                    assertNotNull("Missing resolved resource: " + row, row.getValue("resource"));
                    assertEquals(RESOURCE, row.getValue("resource").stringValue());
                }
                final Value value = row.getValue("label");
                assertTrue("Expected a label literal: " + row, value instanceof Literal);
                assertEquals("en", ((Literal) value).getLanguage());
                assertTrue("Empty label: " + row, !((Literal) value).getLabel().isEmpty());
                assertTrue("Duplicate label: " + row, labels.add(value));
                log.info("DBpedia HTTPS result: " + row);
            }
        } finally {
            result.close();
        }
        return labels;
    }
}
