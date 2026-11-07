/*

Copyright (C) SYSTAP, LLC DBA Blazegraph 2006-2016.  All rights reserved.

Contact:
     SYSTAP, LLC DBA Blazegraph
     2501 Calvert ST NW #106
     Washington, DC 20008
     licenses@blazegraph.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; version 2 of the License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

*/
package com.bigdata.service;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Properties;

import junit.framework.TestCase;

public class TestScaleOutClientFactory extends TestCase {

    public void test_singleConfigurationFile() {
        final String[] files = { "client.config" };
        final ConfiguredClient client = (ConfiguredClient) ScaleOutClientFactory
                .getJiniClient(ConfiguredClient.class.getName(), files);

        assertEquals(Arrays.asList(files), Arrays.asList(client.propertyFiles));
    }

    public void test_arrayConstructorWithMultipleConfigurationFiles() {
        final String[] files = { "client.config", "overrides.config" };
        final ConfiguredClient client = (ConfiguredClient) ScaleOutClientFactory
                .getJiniClient(BothConstructorsClient.class.getName(), files);

        assertEquals(Arrays.asList(files), Arrays.asList(client.propertyFiles));
    }

    public void test_emptyConfigurationArray() {
        final ConfiguredClient client = (ConfiguredClient) ScaleOutClientFactory
                .getJiniClient(ConfiguredClient.class.getName(), new String[0]);

        assertNotNull(client.propertyFiles);
        assertEquals(0, client.propertyFiles.length);
    }

    public void test_nullConfigurationArray() {
        final ConfiguredClient client = (ConfiguredClient) ScaleOutClientFactory
                .getJiniClient(ConfiguredClient.class.getName(), null);

        assertNull(client.propertyFiles);
    }

    public void test_missingProviderPreservesCause() {
        try {
            ScaleOutClientFactory.getJiniClient(getClass().getName()
                    + "$MissingClient", new String[] { "client.config" });
            fail("Expected a missing provider to fail");
        } catch (RuntimeException expected) {
            assertTrue(expected.getCause() instanceof ClassNotFoundException);
        }
    }

    public void test_constructorFailurePreservesCause() {
        try {
            ScaleOutClientFactory.getJiniClient(FailingClient.class.getName(),
                    new String[] { "client.config" });
            fail("Expected the provider constructor to fail");
        } catch (RuntimeException expected) {
            assertTrue(expected.getCause() instanceof InvocationTargetException);
            final Throwable cause = expected.getCause().getCause();
            assertTrue(cause instanceof IllegalStateException);
            assertEquals("provider failure", cause.getMessage());
        }
    }

    public void test_requiresConfigurationArrayConstructor() {
        try {
            ScaleOutClientFactory.getJiniClient(NoArgClient.class.getName(),
                    new String[] { "client.config" });
            fail("Expected a provider without a String[] constructor to fail");
        } catch (RuntimeException expected) {
            assertTrue(expected.getCause() instanceof NoSuchMethodException);
        }
    }

    public static class ConfiguredClient extends EmbeddedClient<Object> {

        final String[] propertyFiles;

        public ConfiguredClient(final String[] propertyFiles) {
            super(new Properties());
            this.propertyFiles = propertyFiles;
        }
    }

    public static class BothConstructorsClient extends ConfiguredClient {

        public BothConstructorsClient() {
            super(null);
            throw new AssertionError("The String[] constructor must be used");
        }

        public BothConstructorsClient(final String[] propertyFiles) {
            super(propertyFiles);
        }
    }

    public static class FailingClient extends ConfiguredClient {

        public FailingClient(final String[] propertyFiles) {
            super(propertyFiles);
            throw new IllegalStateException("provider failure");
        }
    }

    public static class NoArgClient extends EmbeddedClient<Object> {

        public NoArgClient() {
            super(new Properties());
        }
    }
}
