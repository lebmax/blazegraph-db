/*
 * SPDX-License-Identifier: GPL-2.0-only
 */
package com.bigdata.journal.jini.ha;

import java.io.File;
import java.net.URI;

import junit.framework.TestCase2;
import org.openrdf.query.parser.sparql.SPARQLParser;

/**
 * Tests the file URLs used by HA test data loads.
 */
public class TestHAFileUrls extends TestCase2 {

    public TestHAFileUrls() {
        super();
    }

    public TestHAFileUrls(final String name) {
        super(name);
    }

    public void testExistingFoafFileUrl() throws Exception {
        assertFoafFileUrl("data-0.nq.gz");
    }

    public void testFoafFileUrlWithSpecialCharacters() throws Exception {
        assertFoafFileUrl("synthetic name # Ж.nq.gz");
    }

    private void assertFoafFileUrl(final String fileName) throws Exception {
        final AbstractHAJournalServerTestCase fixture =
                new AbstractHAJournalServerTestCase() {
                };
        final String url = fixture.getFoafFileUrl(fileName);
        final URI uri = new URI(url);

        assertTrue("URI must be absolute", uri.isAbsolute());
        assertEquals("file", uri.getScheme());
        assertFalse("file URI must be hierarchical", uri.isOpaque());

        final File expected = new File("src/test/resources/data/foaf", fileName)
                .getAbsoluteFile();
        final File actual = new File(uri).getAbsoluteFile();
        assertEquals(expected, actual);

        if ("data-0.nq.gz".equals(fileName)) {
            assertTrue("Expected FOAF data file is missing", expected.isFile());
        }

        new SPARQLParser().parseUpdate("LOAD <" + url + ">", null);
    }

}
