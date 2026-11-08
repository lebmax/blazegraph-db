/*
 * SPDX-License-Identifier: GPL-2.0-only
 */
package com.bigdata.journal.jini.ha;

import java.util.concurrent.ConcurrentSkipListSet;

import junit.framework.TestCase2;

/**
 * Tests ordering and uniqueness of HALogInfo entries.
 */
public class TestDumpLogDigests extends TestCase2 {

    public TestDumpLogDigests() {
        super();
    }

    public TestDumpLogDigests(final String name) {
        super(name);
    }

    public void testHALogInfoOrderingAndUniqueness() {
        final ConcurrentSkipListSet<DumpLogDigests.HALogInfo> infos =
                new ConcurrentSkipListSet<DumpLogDigests.HALogInfo>();

        assertTrue(infos.add(new DumpLogDigests.HALogInfo(2L, false,
                new byte[] { 2 })));
        assertTrue(infos.add(new DumpLogDigests.HALogInfo(0L, false,
                new byte[] { 0 })));
        assertTrue(infos.add(new DumpLogDigests.HALogInfo(Long.MAX_VALUE,
                false, new byte[] { 3 })));
        assertTrue(infos.add(new DumpLogDigests.HALogInfo(1L, true,
                new byte[] { 1 })));

        assertFalse(infos.add(new DumpLogDigests.HALogInfo(1L, false,
                new byte[] { 99 })));
        assertEquals(4, infos.size());

        final long[] expected = { 0L, 1L, 2L, Long.MAX_VALUE };
        int index = 0;
        for (DumpLogDigests.HALogInfo info : infos) {
            assertTrue(index < expected.length);
            assertEquals(expected[index], info.commitCounter);
            index++;
        }
        assertEquals(expected.length, index);
    }

}
