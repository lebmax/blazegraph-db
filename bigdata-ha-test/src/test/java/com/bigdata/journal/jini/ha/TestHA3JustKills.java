/**

Copyright (C) SYSTAP, LLC 2006-2015.  All rights reserved.

Contact:
     SYSTAP, LLC
     2501 Calvert ST NW #106
     Washington, DC 20008
     licenses@systap.com

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
/*
 * Created on Oct 31, 2012
 */
package com.bigdata.journal.jini.ha;

import java.io.IOException;
import java.io.Serializable;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import junit.framework.TestCase2;

import net.jini.config.Configuration;

import com.bigdata.ha.HAGlue;
import com.bigdata.ha.msg.IHASyncRequest;
import com.bigdata.ha.msg.HAWriteMessage;
import com.bigdata.ha.msg.IHAWriteMessage;
import com.bigdata.journal.StoreTypeEnum;
import com.bigdata.journal.jini.ha.HAJournalServer.HAQuorumService.IHAProgressListener;
import com.bigdata.journal.jini.ha.HAJournalTest.HAGlueTest;

/**
 * Test suite focused on sure kills (kill -9) of processes and correct handling
 * of failover in the face of such sudden process kills.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 */
public class TestHA3JustKills extends AbstractHA3JournalServerTestCase {

    /**
     * {@inheritDoc}
     * <p>
     * Note: This overrides some {@link Configuration} values for the
     * {@link HAJournalServer} in order to establish conditions suitable for
     * testing the {@link ISnapshotPolicy} and {@link IRestorePolicy}.
     */
    @Override
    protected String[] getOverrides() {
        
        return new String[]{
//	        		"com.bigdata.journal.HAJournal.properties=" +TestHA3JournalServer.getTestHAJournalProperties(com.bigdata.journal.HAJournal.properties),
                "com.bigdata.journal.jini.ha.HAJournalServer.restorePolicy=new com.bigdata.journal.jini.ha.DefaultRestorePolicy(0L,1,0)",
                "com.bigdata.journal.jini.ha.HAJournalServer.snapshotPolicy=new com.bigdata.journal.jini.ha.NoSnapshotPolicy()",
//	                "com.bigdata.journal.jini.ha.HAJournalServer.HAJournalClass=\""+HAJournalTest.class.getName()+"\"",
                "com.bigdata.journal.jini.ha.HAJournalServer.onlineDisasterRecovery=true",
        };
        
    }
    
    public TestHA3JustKills() {
    }

    public TestHA3JustKills(String name) {
        super(name);
    }

    /**
     * Start A+B+C in strict sequence. Wait until the quorum fully meets. Start
     * a long running LOAD. While the LOAD is running, sure kill C (the last
     * follower). Verify that the LOAD completes successfully with the remaining
     * services (A+B).
     */
    public void testABC_LiveLoadRemainsMet_kill_C() throws Exception {

        // enforce join order
        final ABC startup = new ABC(true /*sequential*/);

        final long token = awaitFullyMetQuorum();

        // await the initial KB commit on all services.
        awaitCommitCounter(1L, new HAGlue[] { startup.serverA, startup.serverB,
                startup.serverC });

        try (ControlledLoadTask ft = new ControlledLoadTask(token)) {
            executorService.submit(ft);
            ft.awaitStarted();

            // Verify LOAD is active before killing the last follower.
            assertFalse(ft.isDone());

            log.warn("ZOOKEEPER\n" + dumpZoo());
            kill(startup.serverC);

            // Crash detection may wait for the ZooKeeper session to expire.
            awaitPipeline(getZKSessionTimeout() + awaitQuorumTimeout,
                    TimeUnit.MILLISECONDS,
                    new HAGlue[] { startup.serverA, startup.serverB });

            // token must remain unchanged to indicate same quorum
            assertEquals(token, awaitMetQuorum());

            awaitMembers(new HAGlue[] { startup.serverA, startup.serverB });
            awaitJoined(new HAGlue[] { startup.serverA, startup.serverB });

            assertEquals(token, awaitMetQuorum());
            assertFalse(ft.isDone());
            ft.finish();

            // Await successful LOAD completion on the remaining services.
            ft.get(longLoadTimeoutMillis, TimeUnit.MILLISECONDS);

            assertEquals(token, awaitMetQuorum());
            assertDigestsEquals(new HAGlue[] { startup.serverA, startup.serverB });
        }

    }
    
    public void _testStressABC_LiveLoadRemainsMet_kill_C() throws Exception {
        for (int i = 0; i < 5; i++) {
            try {
            	testABC_LiveLoadRemainsMet_kill_C();
            } catch (Throwable t) {
                fail("Run " + i, t);
            } finally {
            	Thread.sleep(1000);
                destroyAll();
            }
        }
    }

    /**
     * A unit test for the receive progress kill trigger. This does not start
     * an HA service.
     */
    public static class TestKillTrigger extends TestCase2 {

        public TestKillTrigger() {
            super();
        }

        public TestKillTrigger(final String name) {
            super(name);
        }

        public void testOpeningCommitCounterTriggersOnlyOneKill()
                throws Exception {

            final CountingKillListener listener = new CountingKillListener();
            final HAWriteMessage msg = new HAWriteMessage(UUID.randomUUID(),
                    1L, 0L, 0L, 2, 0, StoreTypeEnum.RW, 1L, 0L, 0L);

            listener.incReceive(null, msg, 1, 1, 1);
            listener.incReceive(null, msg, 2, 1, 0);

            assertEquals("Repeated reads of the same opening commit must kill once",
                    1, listener.getKillActionCount());
            assertEquals("The configured PID should be passed to the action",
                    123, listener.getLastKilledPid());

        }

        private static final class CountingKillListener extends
                HAProgressListenerKillPID_1 {

            private static final long serialVersionUID = 1L;

            private int killActionCount;
            private int lastKilledPid;

            private CountingKillListener() {
                super(123);
            }

            @Override
            protected void killProcess(final int pid) {
                killActionCount++;
                lastKilledPid = pid;
            }

            private int getKillActionCount() {
                return killActionCount;
            }

            private int getLastKilledPid() {
                return lastKilledPid;
            }

        }

    }

    /**
     * Start A+B+C in strict sequence. Wait until the quorum fully meets. Start
     * a long running LOAD. While the LOAD is running, sure kill B (the first
     * follower). Verify that the LOAD completes successfully with the remaining
     * services (A+C), after the leader re-orders the pipeline.
     */
    public void testABC_LiveLoadRemainsMet_kill_B() throws Exception {

        // enforce join order
        final ABC startup = new ABC(true /*sequential*/);

        final long token = awaitFullyMetQuorum();

        // await the initial KB commit on all services.
        awaitCommitCounter(1L, new HAGlue[] { startup.serverA, startup.serverB,
                startup.serverC });

        try (ControlledLoadTask ft = new ControlledLoadTask(token)) {
            executorService.submit(ft);
            ft.awaitStarted();

            // Verify LOAD is active before killing the first follower.
            assertFalse(ft.isDone());

            log.warn("ZOOKEEPER\n" + dumpZoo());
            kill(startup.serverB);

            // Crash detection may wait for the ZooKeeper session to expire.
            awaitPipeline(getZKSessionTimeout() + awaitQuorumTimeout,
                    TimeUnit.MILLISECONDS,
                    new HAGlue[] { startup.serverA, startup.serverC });

            awaitMembers(new HAGlue[] { startup.serverA, startup.serverC });
            awaitJoined(new HAGlue[] { startup.serverA, startup.serverC });

            // token must remain unchanged to indicate same quorum
            assertEquals(token, awaitMetQuorum());
            assertFalse(ft.isDone());
            ft.finish();

            // Await successful LOAD completion on the remaining services.
            ft.get(longLoadTimeoutMillis, TimeUnit.MILLISECONDS);

            assertEquals(token, awaitMetQuorum());
            assertDigestsEquals(new HAGlue[] { startup.serverA, startup.serverC });
        }

    }

    /**
     * Base class for sure kill of a process when write replication reaches a
     * specific trigger condition.
     * <p>
     * Note: A concrete static instance of the {@link IHAProgressListener} MUST
     * be used since this class MUST be {@link Serializable}. An inner class
     * WILL NOT work since it would attempt to serialize the outer test class.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan
     *         Thompson</a>
     */
    abstract static private class MyHAProgressListenerKillPID implements
            IHAProgressListener, Serializable {

        private static final long serialVersionUID = 1L;
        
        final private int pid;

        private final AtomicBoolean killRequested = new AtomicBoolean();

        /**
         * 
         * @param pid
         *            The process to be killed.
         */
        public MyHAProgressListenerKillPID(final int pid) {
            this.pid = pid;
        }
        
        /** Sure kill of {@link #pid}. */
        protected void sureKill() {

            // A payload may arrive in several reads before the opening commit
            // counter changes. Killing the same PID again must not fail recovery.
            if (!killRequested.compareAndSet(false, true))
                return;

            log.fatal("Will sure kill process: pid=" + pid);

            try {

                killProcess(pid);

            } catch (IOException e) {

                throw new RuntimeException("Could not kill process: pid=" + pid, e);

            } catch (InterruptedException e) {

                Thread.currentThread().interrupt();

                throw new RuntimeException("Interrupted while killing process: pid="
                        + pid, e);

            }

        }

        /**
         * Perform the platform-specific process kill. Kept overridable so the
         * receive-trigger behavior can be tested without starting an OS process.
         */
        protected void killProcess(final int pid) throws IOException,
                InterruptedException {

            final String osName = System.getProperty("os.name", "");

            if (osName.toLowerCase(java.util.Locale.ROOT).startsWith("windows")) {

                final Process taskkill = new ProcessBuilder("taskkill",
                        "/PID", Integer.toString(pid), "/F")
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                        .start();

                final int exitCode = taskkill.waitFor();

                if (exitCode != 0) {

                    throw new IllegalStateException("taskkill failed for pid="
                            + pid + ", exitCode=" + exitCode);

                }

            } else {

                Runtime.getRuntime().exec("kill -KILL " + pid);

            }

        }
    }

    private static class HAProgressListenerKillPID_1 extends
            MyHAProgressListenerKillPID {

        private static final long serialVersionUID = 1L;

        public HAProgressListenerKillPID_1(int pid) {
            super(pid);
        }

        @Override
        public void incReceive(final IHASyncRequest req,
                final IHAWriteMessage msg, final int nreads, final int rdlen,
                final int rem) throws Exception {
            
            log.error("msg=" + msg + ", nreads=" + nreads + ", rdlen=" + rdlen
                    + ", rem=" + rem);
            
            /*
             * Note: This is the *opening* root block counter. The process will
             * be killed as soon as it has received the first chunk of data for
             * the payload of the first replicated write cache block.
             */
  
            if (msg.getCommitCounter() == 1L) {

                sureKill();

            }

        }

    }

    /**
     * Start A+B+C in strict sequence. Wait until the quorum fully meets. Start
     * a long running LOAD. While the LOAD is running, sure kill B (the first
     * follower) when it has received a specified number of bytes of data and is
     * attempting to incrementally replicate that data to C (the 2nd follower).
     * Verify that the LOAD completes successfully with the remaining services
     * (A+C), after the leader re-orders the pipeline.
     */
    public void testABC_LiveLoadRemainsMet_kill_B_duringIncrementalReplication()
            throws Exception {

        // enforce join order
        final ABC startup = new ABC(true /* sequential */);

        final long token = awaitFullyMetQuorum();

        // await the initial KB commit on all services.
        awaitCommitCounter(1L, new HAGlue[] { startup.serverA, startup.serverB,
                startup.serverC });

        // Set a trigger to sure kill B once it reaches the specified
        // replication point.
        ((HAGlueTest) startup.serverB)
                .failWriteReplication(new HAProgressListenerKillPID_1(
                        ((HAGlueTest) startup.serverB).getPID()));

        // start concurrent task loads that continue until fully met
        final FutureTask<Void> ft = new FutureTask<Void>(new LargeLoadTask(
                token));

        executorService.submit(ft);

        // Wait until B is killed.
        assertCondition(new Runnable() {
            public void run() {
                try {
                    startup.serverB.getWritePipelineAddr();
                    fail("B is still running.");
                } catch (IOException ex) {
                    if (log.isInfoEnabled())
                        log.info("Expected exception: B is no longer responding: "
                                + ex);
                    return;
                }
            }
        }, 20000, TimeUnit.MILLISECONDS);

        // Verify load is still running.
        assertFalse(ft.isDone());

        // Dump Zookeeper
        log.warn("ZOOKEEPER\n" + dumpZoo());

        // Note: automatic.
//        startup.serverA.submit(new ForceRemoveService(getServiceBId()), true)
//                .get();

        awaitPipeline(10, TimeUnit.SECONDS, new HAGlue[] { startup.serverA,
                startup.serverC });

        // also check members and joined
        awaitMembers(new HAGlue[] { startup.serverA, startup.serverC });
        awaitJoined(new HAGlue[] { startup.serverA, startup.serverC });

        // token must remain unchanged to indicate same quorum
        assertEquals(token, awaitMetQuorum());

        // Await LOAD, but with a timeout.
        ft.get(longLoadTimeoutMillis, TimeUnit.MILLISECONDS);

        // token must remain unchanged to indicate same quorum
        assertEquals(token, awaitMetQuorum());

    }

    /**
     * Start A+B+C in strict sequence. Wait until the quorum fully meets. Start
     * a long running LOAD. While the LOAD is running, sure kill C (the 2nd
     * follower) when it has received a specified number of bytes of data.
     * Verify that the LOAD completes successfully with the remaining services
     * (A+B).
     */
    public void testABC_LiveLoadRemainsMet_kill_C_duringIncrementalReplication()
            throws Exception {

        // enforce join order
        final ABC startup = new ABC(true /* sequential */);

        final long token = awaitFullyMetQuorum();

        // await the initial KB commit on all services.
        awaitCommitCounter(1L, new HAGlue[] { startup.serverA, startup.serverB,
                startup.serverC });

        // Set a trigger to sure kill C once it reaches the specified
        // replication point.
        ((HAGlueTest) startup.serverC)
                .failWriteReplication(new HAProgressListenerKillPID_1(
                        ((HAGlueTest) startup.serverC).getPID()));

        // start concurrent task loads that continue until fully met
        final FutureTask<Void> ft = new FutureTask<Void>(new LargeLoadTask(
                token));

        executorService.submit(ft);

        // Wait until C is killed.
        assertCondition(new Runnable() {
            public void run() {
                try {
                    startup.serverC.getWritePipelineAddr();
                    fail("C is still running.");
                } catch (IOException ex) {
                    if (log.isInfoEnabled())
                        log.info("Expected exception: C is no longer responding: "
                                + ex);
                    return;
                }
            }
        }, 20000, TimeUnit.MILLISECONDS);

        // Verify load is still running.
        assertFalse(ft.isDone());

        // Dump Zookeeper
        log.warn("ZOOKEEPER\n" + dumpZoo());

        // Note: automatic.
//        startup.serverA.submit(new ForceRemoveService(getServiceCId()), true)
//                .get();

        awaitPipeline(10, TimeUnit.SECONDS, new HAGlue[] { startup.serverA,
                startup.serverB });

        // also check members and joined
        awaitMembers(new HAGlue[] { startup.serverA, startup.serverB });
        awaitJoined(new HAGlue[] { startup.serverA, startup.serverB });

        // token must remain unchanged to indicate same quorum
        assertEquals(token, awaitMetQuorum());

        // Await LOAD, but with a timeout.
        ft.get(longLoadTimeoutMillis, TimeUnit.MILLISECONDS);

        // token must remain unchanged to indicate same quorum
        assertEquals(token, awaitMetQuorum());

    }

    /**
     * Start A+B+C in strict sequence. Wait until the quorum fully meets. Start
     * a long running LOAD. While the LOAD is running, C will issue a sure kill
     * of B (the 1st follower) when it has received a specified number of bytes
     * of data from B. Verify that the LOAD completes successfully with the
     * remaining services (A+C).
     */
    public void testABC_LiveLoadRemainsMet_C_kills_B_duringIncrementalReplication()
            throws Exception {

        // enforce join order
        final ABC startup = new ABC(true /* sequential */);

        final long token = awaitFullyMetQuorum();

        // await the initial KB commit on all services.
        awaitCommitCounter(1L, new HAGlue[] { startup.serverA, startup.serverB,
                startup.serverC });

        // Set a trigger to sure kill B once C reaches the specified
        // replication point.
        ((HAGlueTest) startup.serverC)
                .failWriteReplication(new HAProgressListenerKillPID_1(
                        ((HAGlueTest) startup.serverB).getPID()));

        // start large load.
        final FutureTask<Void> ft = new FutureTask<Void>(new LargeLoadTask(
                token));

        executorService.submit(ft);

        // Wait until B is killed.
        assertCondition(new Runnable() {
            public void run() {
                try {
                    startup.serverB.getWritePipelineAddr();
                    fail("B is still running.");
                } catch (IOException ex) {
                    if (log.isInfoEnabled())
                        log.info("Expected exception: B is no longer responding: "
                                + ex);
                    return;
                }
            }
        }, 20000, TimeUnit.MILLISECONDS);

        // Verify load is still running.
        assertFalse(ft.isDone());

        // Dump Zookeeper
        log.warn("ZOOKEEPER\n" + dumpZoo());

        // Note: automatic.
//        startup.serverA.submit(new ForceRemoveService(getServiceCId()), true)
//                .get();

        awaitPipeline(10, TimeUnit.SECONDS, new HAGlue[] { startup.serverA,
                startup.serverC });

        // also check members and joined
        awaitMembers(new HAGlue[] { startup.serverA, startup.serverC });
        awaitJoined(new HAGlue[] { startup.serverA, startup.serverC });

        // token must remain unchanged to indicate same quorum
        assertEquals(token, awaitMetQuorum());

        // Await LOAD, but with a timeout.
        ft.get(longLoadTimeoutMillis, TimeUnit.MILLISECONDS);

        // token must remain unchanged to indicate same quorum
        assertEquals(token, awaitMetQuorum());

    }

    /**
     * Test where we start A+B+C in strict sequence. Once we observe that all
     * services have gone through the initial KB create, we do a sudden kill of
     * B. We then start the live load. This test explores what happens when A is
     * not yet aware that B is dead when the UPDATE operation starts.
     * 
     * @throws Exception
     */
    public void testABC_awaitKBCreate_killB_LiveLoadRemainsMet()
            throws Exception {

        // enforce join order
        final ABC startup = new ABC(true /* sequential */);

        final long token = awaitFullyMetQuorum();

        // await the initial KB commit on all services.
        awaitCommitCounter(1L, new HAGlue[] { startup.serverA, startup.serverB,
                startup.serverC });

        kill(startup.serverB);

        // start concurrent task loads that continue until fully met
        final FutureTask<Void> ft = new FutureTask<Void>(new LargeLoadTask(
                token));

        executorService.submit(ft);

        awaitPipeline(getZKSessionTimeout() + 5000, TimeUnit.MILLISECONDS,
                new HAGlue[] { startup.serverA, startup.serverC });

        // also check members and joined
        awaitMembers(new HAGlue[] { startup.serverA, startup.serverC });
        awaitJoined(new HAGlue[] { startup.serverA, startup.serverC });

        // token must remain unchanged to indicate same quorum
        assertEquals(token, awaitMetQuorum());

        // Await LOAD, but with a timeout.
        ft.get(longLoadTimeoutMillis, TimeUnit.MILLISECONDS);

        // token must remain unchanged to indicate same quorum
        assertEquals(token, awaitMetQuorum());
        
    }

    /**
     * Test where we start A+B+C in strict sequence. Once we observe that all
     * services have gone through the initial KB create, we do a sudden kill of
     * C. We then start the live load. This test explores what happens when A
     * and B are not yet aware that C is dead when the UPDATE operation starts.
     * 
     * @throws Exception
     */
    public void testABC_awaitKBCreate_killC_LiveLoadRemainsMet()
            throws Exception {

        // enforce join order
        final ABC startup = new ABC(true /* sequential */);

        final long token = awaitFullyMetQuorum();

        // await the initial KB commit on all services.
        awaitCommitCounter(1L, new HAGlue[] { startup.serverA, startup.serverB,
                startup.serverC });

        kill(startup.serverC);

        // start concurrent task loads that continue until fully met
        final FutureTask<Void> ft = new FutureTask<Void>(new LargeLoadTask(
                token));

        executorService.submit(ft);

        // Note: Automatic.
//        startup.serverA.submit(new ForceRemoveService(getServiceCId()), true)
//                .get();

        awaitPipeline(getZKSessionTimeout() + 5000, TimeUnit.MILLISECONDS,
                new HAGlue[] { startup.serverA, startup.serverB });

        // also check members and joined
        awaitMembers(new HAGlue[] { startup.serverA, startup.serverB });
        awaitJoined(new HAGlue[] { startup.serverA, startup.serverB });

        // token must remain unchanged to indicate same quorum
        assertEquals(token, awaitMetQuorum());

        // Await LOAD, but with a timeout.
        ft.get(longLoadTimeoutMillis, TimeUnit.MILLISECONDS);

        // token must remain unchanged to indicate same quorum
        assertEquals(token, awaitMetQuorum());
        
    }

}
