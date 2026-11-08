/*
 * SPDX-License-Identifier: GPL-2.0-only
 */
package com.bigdata.journal.jini.ha;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import junit.framework.AssertionFailedError;
import junit.framework.TestCase2;

import com.bigdata.ha.HAGlue;
import com.bigdata.ha.msg.HALogDigestResponse;

/**
 * Regression tests for checking that HALog purge has completed.
 */
public class TestHALogPurge extends TestCase2 {

    private final AbstractHAJournalServerTestCase fixture =
            new AbstractHAJournalServerTestCase() {
            };

    public TestHALogPurge() {
        super();
    }

    public TestHALogPurge(final String name) {
        super(name);
    }

    public void testWaitsForHistoricalHALogToBePurged() throws Exception {
        final int[] calls = { 0 };
        final HAGlue service = newService(new InvocationHandler() {
            @Override
            public Object invoke(final Object proxy, final Method method,
                    final Object[] args) throws Throwable {
                if ("computeHALogDigest".equals(method.getName())) {
                    if (++calls[0] <= 2) {
                        return new HALogDigestResponse(1L,
                                new byte[] { 1 });
                    }
                    throw new FileNotFoundException("HALog was purged");
                }
                return objectMethod(proxy, method, args);
            }
        });

        fixture.assertHALogNotFound(1L, 1L, new HAGlue[] { service });

        assertTrue("expected a retry after the first two existing HALogs",
                calls[0] >= 3);
    }

    public void testWrappedFileNotFoundMeansHALogIsAbsent() throws Exception {
        final HAGlue service = newService(new InvocationHandler() {
            @Override
            public Object invoke(final Object proxy, final Method method,
                    final Object[] args) throws Throwable {
                if ("computeHALogDigest".equals(method.getName())) {
                    throw new IOException("remote call failed",
                            new FileNotFoundException("HALog is absent"));
                }
                return objectMethod(proxy, method, args);
            }
        });

        fixture.assertHALogNotFound(1L, 1L, new HAGlue[] { service });
    }

    public void testConnectionFailureIsRethrown() throws Exception {
        final IOException expected = new IOException("connection failed");
        final HAGlue service = newService(new InvocationHandler() {
            @Override
            public Object invoke(final Object proxy, final Method method,
                    final Object[] args) throws Throwable {
                if ("computeHALogDigest".equals(method.getName())) {
                    throw expected;
                }
                return objectMethod(proxy, method, args);
            }
        });

        try {
            fixture.assertHALogNotFound(1L, 1L, new HAGlue[] { service });
            fail("expected the connection failure to be rethrown");
        } catch (IOException actual) {
            assertSame(expected, actual);
        }
    }

    public void testPermanentHALogStillFails() throws Exception {
        final int[] calls = { 0 };
        final HAGlue service = newService(new InvocationHandler() {
            @Override
            public Object invoke(final Object proxy, final Method method,
                    final Object[] args) throws Throwable {
                if ("computeHALogDigest".equals(method.getName())) {
                    calls[0]++;
                    return new HALogDigestResponse(1L, new byte[] { 1 });
                }
                return objectMethod(proxy, method, args);
            }
        });

        boolean failed = false;
        try {
            fixture.assertHALogNotFound(1L, 1L, new HAGlue[] { service });
        } catch (AssertionFailedError expected) {
            failed = true;
        }

        assertTrue("a permanently present HALog must fail the assertion",
                failed);
        assertTrue("the service must be queried", calls[0] > 0);
    }

    public void testInterruptedWaitFailsAndPreservesInterrupt() throws Exception {
        final HAGlue service = newService(new InvocationHandler() {
            @Override
            public Object invoke(final Object proxy, final Method method,
                    final Object[] args) throws Throwable {
                if ("computeHALogDigest".equals(method.getName())) {
                    return new HALogDigestResponse(1L, new byte[] { 1 });
                }
                return objectMethod(proxy, method, args);
            }
        });

        try {
            Thread.currentThread().interrupt();
            try {
                fixture.assertHALogNotFound(1L, 1L, new HAGlue[] { service });
                fail("an interrupted purge wait must fail");
            } catch (InterruptedIOException expected) {
                assertTrue(Thread.currentThread().isInterrupted());
            }
        } finally {
            Thread.interrupted();
        }
    }

    private static HAGlue newService(final InvocationHandler handler) {
        return (HAGlue) Proxy.newProxyInstance(HAGlue.class.getClassLoader(),
                new Class<?>[] { HAGlue.class }, handler);
    }

    private static Object objectMethod(final Object proxy,
            final Method method, final Object[] args) {
        if ("toString".equals(method.getName())) {
            return "test-HAGlue";
        }
        if ("hashCode".equals(method.getName())) {
            return System.identityHashCode(proxy);
        }
        if ("equals".equals(method.getName())) {
            return proxy == args[0];
        }
        throw new UnsupportedOperationException(method.toString());
    }

}
