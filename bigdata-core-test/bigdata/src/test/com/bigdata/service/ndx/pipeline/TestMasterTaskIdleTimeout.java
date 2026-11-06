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
/*
 * Created on Apr 16, 2009
 */

package com.bigdata.service.ndx.pipeline;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.bigdata.btree.keys.KVO;
import com.bigdata.relation.accesspath.BlockingBuffer;

/**
 * Unit tests of the idle timeout behavior for {@link AbstractMasterTask} and
 * friends.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class TestMasterTaskIdleTimeout extends AbstractMasterTestCase {

    public TestMasterTaskIdleTimeout() {
    }

    public TestMasterTaskIdleTimeout(String name) {
        super(name);
    }
    
    /**
     * Unit test to verify that output buffers are not closed too quickly. This
     * also verifies that a buffer automatically re-opened if it was closed by a
     * timeout.
     */
    public void test_idleTimeout() throws InterruptedException,
            ExecutionException {

        final H masterStats = new H();

        final BlockingBuffer<KVO<O>[]> masterBuffer = new BlockingBuffer<KVO<O>[]>(
                masterQueueCapacity);

        final M master = new M(masterStats, masterBuffer, executorService,
                TimeUnit.MILLISECONDS.toNanos(2000)/* sinkIdleTimeout */,
                M.DEFAULT_SINK_POLL_TIMEOUT) {
          
            protected BlockingBuffer<KVO<O>[]> newSubtaskBuffer() {

                return new BlockingBuffer<KVO<O>[]>(
                        new ArrayBlockingQueue<KVO<O>[]>(subtaskQueueCapacity), //
                        10,// chunkSize
                        20,// chunkTimeout
                        TimeUnit.MILLISECONDS,// chunkTimeoutUnit
                        true // ordered
                );

            }

        };

        // Wrap computation as FutureTask.
        final FutureTask<H> ft = new FutureTask<H>(master);
        
        // Set Future on BlockingBuffer.
        masterBuffer.setFuture(ft);
        
        // Start the consumer.
        executorService.submit(ft);
        
        /*
         * write a chunk on the buffer. this will cause an output buffer to be
         * created.
         */
        final long beforeWrite = System.nanoTime();
        {
            final KVO<O>[] a = new KVO[] { //
                    new KVO<O>(new byte[] { 1 }, new byte[] { 2 }, null/* val */), };

            masterBuffer.add(a);

        }

        // wait just a bit for that chunk to be output by the sink.
        awaitChunksOut(master, 1, 100/* MUST be GT sink's chunkTimeout */,
                TimeUnit.MILLISECONDS);

        // verify chunk was output by the sink.
        assertEquals("elementsIn", 1, masterStats.elementsIn.get());
        assertEquals("chunksIn", 1, masterStats.chunksIn.get());
        assertEquals("elementsOut", 1, masterStats.elementsOut.get());
        assertEquals("chunksOut", 1, masterStats.chunksOut.get());
        assertEquals("partitionCount", 1, masterStats.getMaximumPartitionCount());
        
        // make sure that the subtask is still running.
        assertEquals("subtaskStartCount", 1, masterStats.subtaskStartCount.get());
        assertEquals("subtaskEndCount", 0, masterStats.subtaskEndCount.get());

        final long elapsed1 = System.nanoTime() - beforeWrite;
        
        /*
         * Sleep for up to 1/2 of the remaining idle timeout.
         * 
         * Note: If the idle timeout is too short then Thread#sleep() will not
         * return before the timeout has expired.
         */
        Thread.sleep(Math.max(1,TimeUnit.NANOSECONDS.toMillis(master.sinkIdleTimeoutNanos
                / 2 - elapsed1)));

        final long elapsed2 = System.nanoTime() - beforeWrite;

        if (elapsed2 > master.sinkIdleTimeoutNanos) {

            fail("Sleep too long - idle timeout may have expired.");

        }

        // the original subtask should still be running.
        assertEquals("subtaskStartCount", 1, masterStats.subtaskStartCount.get());
        assertEquals("subtaskEndCount", 0, masterStats.subtaskEndCount.get());

        // now sleep for the entire idle timeout (this give us some padding).
        Thread
                .sleep(TimeUnit.NANOSECONDS
                        .toMillis(master.sinkIdleTimeoutNanos));

        // the subtask should have terminated and no other subtask should have started.
        assertEquals("subtaskStartCount", 1, masterStats.subtaskStartCount.get());
        assertEquals("subtaskEndCount", 1, masterStats.subtaskEndCount.get());
        assertEquals("subtaskIdleTimeout", 1, masterStats.subtaskIdleTimeoutCount.get());

        /*
         * write another chunk onto the same output buffer.
         */
        {
            final KVO<O>[] a = new KVO[] { //
                    new KVO<O>(new byte[] { 1 }, new byte[] { 3 }, null/* val */), };

            masterBuffer.add(a);

        }
        
        // close the master
        masterBuffer.close();

        // and await its future.
        masterBuffer.getFuture().get();

        // verify 2nd chunk was output by the sink.
        assertEquals("elementsIn", 2, masterStats.elementsIn.get());
        assertEquals("chunksIn", 2, masterStats.chunksIn.get());
        assertEquals("elementsOut", 2, masterStats.elementsOut.get());
        assertEquals("chunksOut", 2, masterStats.chunksOut.get());
        assertEquals("partitionCount", 1, masterStats.getMaximumPartitionCount());

        // verify that another subtask was started by the 2nd write.
        assertEquals("subtaskStartCount", 2, masterStats.subtaskStartCount.get());
        assertEquals("subtaskEndCount", 2, masterStats.subtaskEndCount.get());

        /*
         * The idle timeout count should still be one since the 2nd instance of
         * the sink should have been closed when the master was exhausted not by
         * an idle timeout.
         */
        assertEquals("subtaskIdleTimeout", 1, masterStats.subtaskIdleTimeoutCount.get());

        // verify writes on each expected partition.
        {

            final HS subtaskStats = masterStats.getSubtaskStats(new L(1));

            assertNotNull(subtaskStats);

            assertEquals("chunksOut", 2, subtaskStats.chunksOut.get());
            assertEquals("elementsOut", 2, subtaskStats.elementsOut.get());

        }

    }

    /**
     * Unit test verifies that an idle timeout may be LT the chunk timeout and
     * that the sink will be closed by the idle timeout if chunks do not appear
     * in a timely manner.
     * 
     * @throws InterruptedException
     * @throws ExecutionException
     */
    public void test_idleTimeout_LT_chunkTimeout() throws InterruptedException, ExecutionException {

        final H masterStats = new H();

        final long sinkIdleTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(50);

        final long sinkChunkTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(140);
        
        // make sure there is enough room for the test to succeed.
        assertTrue(sinkIdleTimeoutNanos * 2 <= sinkChunkTimeoutNanos);

        final BlockingBuffer<KVO<O>[]> masterBuffer = new BlockingBuffer<KVO<O>[]>(
                masterQueueCapacity);

        final M master = new M(masterStats, masterBuffer, executorService,
                sinkIdleTimeoutNanos,
                M.DEFAULT_SINK_POLL_TIMEOUT) {
          
            protected BlockingBuffer<KVO<O>[]> newSubtaskBuffer() {

                return new BlockingBuffer<KVO<O>[]>(
                        new ArrayBlockingQueue<KVO<O>[]>(subtaskQueueCapacity), //
                        10,// chunkSize
                        sinkChunkTimeoutNanos, TimeUnit.NANOSECONDS,// chunkTimeoutUnit
                        true // ordered
                );

            }

        };

        // Wrap computation as FutureTask.
        final FutureTask<H> ft = new FutureTask<H>(master);

        // Set Future on BlockingBuffer
        masterBuffer.setFuture(ft);

        // Start the consumer.
        executorService.submit(ft);

        /*
         * write a chunk on the buffer. this will cause a sink to be created.
         */
        final long beginWrite = System.nanoTime();
        {
            final KVO<O>[] a = new KVO[] { //
                    new KVO<O>(new byte[] { 1 }, new byte[] { 2 }, null/* val */), };

            masterBuffer.add(a);

        }

        // wait just a bit for that chunk to be output by the sink.
        awaitChunksOut(master, 1, sinkIdleTimeoutNanos
                + (sinkIdleTimeoutNanos / 2), TimeUnit.NANOSECONDS);

        final long elapsed = System.nanoTime() - beginWrite;
        if (log.isInfoEnabled())
            log.info("elapsed=" + elapsed + " "
                    + (elapsed < sinkChunkTimeoutNanos ? "LT" : "GTE")
                    + " chunkTimeout=" + sinkChunkTimeoutNanos);
        
        // verify chunk was output by the sink.
        assertEquals("elementsIn", 1, masterStats.elementsIn.get());
        assertEquals("chunksIn", 1, masterStats.chunksIn.get());
        assertEquals("elementsOut", 1, masterStats.elementsOut.get());
        assertEquals("chunksOut", 1, masterStats.chunksOut.get());
        assertEquals("partitionCount", 1, masterStats.getMaximumPartitionCount());
        
        // verify the sink was closed by an idle timeout.
        assertEquals("subtaskIdleTimeout", 1, masterStats.subtaskIdleTimeoutCount.get());

        /*
         * Verify that the sink was closed before a chunk timeout would have
         * occurred.
         * 
         * Note: if this assertion is triggered then you might have to adjust
         * the chunk timeout to be longer. For example, this could be triggered
         * if the host was swapping since the timings would be far off their
         * nominal values.
         */
        assertTrue("test did not complete before chunk timeout: elapsed="
                + elapsed + ", chunkTimeout=" + sinkChunkTimeoutNanos,
                elapsed <= sinkChunkTimeoutNanos);
        
        // close the master
        masterBuffer.close();

        // and await its future.
        masterBuffer.getFuture().get();

        // verify only one sink started/ended
        assertEquals("subtaskStartCount", 1, masterStats.subtaskStartCount.get());
        assertEquals("subtaskEndCount", 1, masterStats.subtaskEndCount.get());
    }

    /**
     * Verify that a sink stays open and flushes batches on the chunk timeout
     * while new input keeps arriving before the shorter idle timeout.
     */
    public void test_idleTimeout_LT_chunkTimeout2()
            throws InterruptedException, ExecutionException, TimeoutException {

        final long sinkIdleTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(250);
        final long sinkChunkTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(500);
        final long producerDelayMillis = 25;
        final int maxInputChunks = 100;
        final H masterStats = new H();

        // Send one element per chunk and combine only at the sink.
        final BlockingBuffer<KVO<O>[]> masterBuffer = new BlockingBuffer<KVO<O>[]>(
                masterQueueCapacity, 1, 0L, TimeUnit.NANOSECONDS);

        final M master = new M(masterStats, masterBuffer, executorService,
                sinkIdleTimeoutNanos, M.DEFAULT_SINK_POLL_TIMEOUT) {
            @Override
            protected BlockingBuffer<KVO<O>[]> newSubtaskBuffer() {
                // All output chunks must be partial, even if all input is queued.
                return new BlockingBuffer<KVO<O>[]>(
                        new ArrayBlockingQueue<KVO<O>[]>(subtaskQueueCapacity),
                        maxInputChunks + 1, sinkChunkTimeoutNanos,
                        TimeUnit.NANOSECONDS, true);
            }
        };

        final FutureTask<H> future = new FutureTask<H>(master);
        masterBuffer.setFuture(future);
        executorService.submit(future);

        try {
            int chunksSent = 0;
            while (chunksSent < maxInputChunks && masterStats.chunksOut.get() < 2) {
                final KVO<O>[] chunk = new KVO[] {
                        new KVO<O>(new byte[] { 1 }, new byte[] { 2 }, null),
                };
                assertTrue("Master did not accept input",
                        masterBuffer.add(chunk, 1, TimeUnit.SECONDS));
                chunksSent++;
                Thread.sleep(producerDelayMillis);
            }

            synchronized (masterStats) {
                assertTrue("Sink should flush twice while input is open",
                        masterStats.chunksOut.get() >= 2);
                assertTrue("Sink should combine input chunks before shutdown",
                        masterStats.elementsOut.get() > masterStats.chunksOut.get());
            }

            masterBuffer.close();
            future.get(10, TimeUnit.SECONDS);

            assertEquals("masterBuffer.elementsIn", chunksSent, masterBuffer.getElementsAddedCount());
            assertEquals("masterBuffer.chunksIn", chunksSent, masterBuffer.getChunksAddedCount());
            assertEquals("elementsOut", chunksSent, masterStats.elementsOut.get());
            assertEquals("partitionCount", 1, masterStats.getMaximumPartitionCount());
            assertEquals("subtaskIdleTimeout", 0, masterStats.subtaskIdleTimeoutCount.get());
            assertEquals("subtaskStartCount", 1, masterStats.subtaskStartCount.get());
            assertEquals("subtaskEndCount", 1, masterStats.subtaskEndCount.get());
        } finally {
            masterBuffer.close();
            future.cancel(true);
        }
    }

    /**
     * Unit test verifies correct shutdown when writing chunks onto a master
     * whose subtask has an infinite chunk timeout and an infinite idle timeout.
     * In this situation the subtask must notice that the master's input buffer
     * has been closed and close its input buffer so that it will process any
     * elements remaining in that buffer and then terminate.
     * 
     * @throws InterruptedException
     * @throws ExecutionException
     */
    public void test_idleTimeoutInfinite_chunkTimeoutInfinite() throws InterruptedException,
            ExecutionException {

        final H masterStats = new H();

        final BlockingBuffer<KVO<O>[]> masterBuffer = new BlockingBuffer<KVO<O>[]>(
                masterQueueCapacity);

        final M master = new M(masterStats, masterBuffer, executorService,
                Long.MAX_VALUE/* sinkIdleTimeout */, M.DEFAULT_SINK_POLL_TIMEOUT) {

            /**
             * The subtask will use a buffer with an infinite chunk timeout.
             */
            @Override
            protected BlockingBuffer<KVO<O>[]> newSubtaskBuffer() {

                return new BlockingBuffer<KVO<O>[]>(
                        new ArrayBlockingQueue<KVO<O>[]>(subtaskQueueCapacity), //
                        BlockingBuffer.DEFAULT_MINIMUM_CHUNK_SIZE,// 
                        Long.MAX_VALUE, TimeUnit.SECONDS,// chunkTimeout
                        true // ordered
                );

            }
            
        };
        
        // Wrap computation as FutureTask.
        final FutureTask<H> ft = new FutureTask<H>(master);
        
        // Set Future on BlockingBuffer.
        masterBuffer.setFuture(ft);

        // Start the consumer.
        executorService.submit(ft);
        
        // write a chunk on the master.
        {
            final KVO<O>[] a = new KVO[] {
                    new KVO<O>(new byte[] { 1 }, new byte[] { 1 }, null/* val */)
            };

            masterBuffer.add(a);

        }
        
        /*
         * Sleep long enough that we may assume that the chunk has been
         * propagated by the master to the subtask.
         */ 
        Thread.sleep(1000/* ms */);

        // verify that the master has accepted the 1st chunk. 
        assertEquals("elementsIn", 1, masterStats.elementsIn.get());
        assertEquals("chunksIn", 1, masterStats.chunksIn.get());
        
        // verify that nothing has been output yet.
        assertEquals("elementsOut", 0, masterStats.elementsOut.get());
        assertEquals("chunksOut", 0, masterStats.chunksOut.get());

        // write another chunk on the master (distinct values).
        {
            final KVO<O>[] a = new KVO[] {
                    new KVO<O>(new byte[] { 1 }, new byte[] { 2 }, null/* val */)
            };

            masterBuffer.add(a);
            
        }

        // sleep some more.
        Thread.sleep(1000/* ms */);

        // verify that the master has accepted the 2nd chunk. 
        assertEquals("elementsIn", 2, masterStats.elementsIn.get());
        assertEquals("chunksIn", 2, masterStats.chunksIn.get());
        
        // verify that nothing has been output yet.
        assertEquals("elementsOut", 0, masterStats.elementsOut.get());
        assertEquals("chunksOut", 0, masterStats.chunksOut.get());

        // close the master - the output sink should now emit a chunk.
        masterBuffer.close();

        // await the future.
        masterBuffer.getFuture().get();
        
        // verify elements in/out; chunks in/out
        assertEquals("elementsIn", 2, masterStats.elementsIn.get());
        assertEquals("chunksIn", 2, masterStats.chunksIn.get());
        
        assertEquals("elementsOut", 2, masterStats.elementsOut.get());
        assertEquals("chunksOut", 1, masterStats.chunksOut.get());
        
        assertEquals("partitionCount", 1, masterStats.getMaximumPartitionCount());

    }

    /**
     * Unit test verifies that an idle timeout will cause a sink to flush its
     * buffer without waiting to combine additional chunks even though it has an
     * infinite chunkTimeout.
     * 
     * @throws InterruptedException
     * @throws ExecutionException
     * @throws TimeoutException
     */
    public void test_idleTimeout_with_infiniteChunkTimeout()
            throws InterruptedException, ExecutionException, TimeoutException {

        final long sinkIdleTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(100);

        final H masterStats = new H();

        final BlockingBuffer<KVO<O>[]> masterBuffer = new BlockingBuffer<KVO<O>[]>(
                masterQueueCapacity);

        final M master = new M(masterStats, masterBuffer, executorService,
                sinkIdleTimeoutNanos, M.DEFAULT_SINK_POLL_TIMEOUT) {

            /**
             * The subtask will use a buffer with an infinite chunk timeout.
             */
            @Override
            protected BlockingBuffer<KVO<O>[]> newSubtaskBuffer() {

                return new BlockingBuffer<KVO<O>[]>(
                        new ArrayBlockingQueue<KVO<O>[]>(subtaskQueueCapacity), //
                        BlockingBuffer.DEFAULT_MINIMUM_CHUNK_SIZE,// 
                        Long.MAX_VALUE, TimeUnit.SECONDS,// chunkTimeout (infinite)
                        true // ordered
                );

            }
            
        };
        
        // Wrap computation as FutureTask.
        final FutureTask<H> ft = new FutureTask<H>(master);
        
        // Set Future on BlockingBuffer.
        masterBuffer.setFuture(ft);

        // Start the consumer.
        executorService.submit(ft);

        // write a chunk on the master.
        {
            final KVO<O>[] a = new KVO[] {
                    new KVO<O>(new byte[] { 1 }, new byte[] { 1 }, null/* val */)
            };

            masterBuffer.add(a);

        }

        /*
         * Sleep long enough that we may assume that the chunk has been
         * propagated by the master to the subtask but less than the subtask
         * idle timeout.
         */ 
        Thread.sleep(TimeUnit.NANOSECONDS.toMillis(sinkIdleTimeoutNanos) / 2);

        // verify that the master has accepted the 1st chunk. 
        assertEquals("elementsIn", 1, masterStats.elementsIn.get());
        assertEquals("chunksIn", 1, masterStats.chunksIn.get());
        
        // verify that nothing has been output yet.
        assertEquals("elementsOut", 0, masterStats.elementsOut.get());
        assertEquals("chunksOut", 0, masterStats.chunksOut.get());

        // verify subtask has started but not yet ended.
        assertEquals("subtaskStartCount", 1, masterStats.subtaskStartCount.get());
        assertEquals("subtaskEndCount", 0, masterStats.subtaskEndCount.get());
        assertEquals("subtaskIdleTimeout", 0, masterStats.subtaskIdleTimeoutCount.get());
        assertEquals("partitionCount", 1, masterStats.getMaximumPartitionCount());

        // write another chunk on the master (distinct values).
        {
            final KVO<O>[] a = new KVO[] {
                    new KVO<O>(new byte[] { 1 }, new byte[] { 2 }, null/* val */)
            };

            masterBuffer.add(a);
            
        }

        // sleep some more, again not exceeding the subtask idle timeout.
        Thread.sleep(TimeUnit.NANOSECONDS.toMillis(sinkIdleTimeoutNanos) / 2);

        // verify that the master has accepted the 2nd chunk. 
        assertEquals("elementsIn", 2, masterStats.elementsIn.get());
        assertEquals("chunksIn", 2, masterStats.chunksIn.get());
        
        // verify that nothing has been output yet.
        assertEquals("elementsOut", 0, masterStats.elementsOut.get());
        assertEquals("chunksOut", 0, masterStats.chunksOut.get());

        // verify the same subtask is still running.
        assertEquals("subtaskStartCount", 1, masterStats.subtaskStartCount.get());
        assertEquals("subtaskEndCount", 0, masterStats.subtaskEndCount.get());
        assertEquals("subtaskIdleTimeout", 0, masterStats.subtaskIdleTimeoutCount.get());
        assertEquals("partitionCount", 1, masterStats.getMaximumPartitionCount());

        /*
         * Sleep long enough for the subtask idle timeout to be exceeded. The
         * subtask should emit a chunk which combines the two chunks written on
         * it by the master and then close itself.
         */
        Thread.sleep(TimeUnit.NANOSECONDS.toMillis(sinkIdleTimeoutNanos) * 2);
        
        // verify elements in/out; chunks in/out
        assertEquals("elementsIn", 2, masterStats.elementsIn.get());
        assertEquals("chunksIn", 2, masterStats.chunksIn.get());
        
        assertEquals("elementsOut", 2, masterStats.elementsOut.get());
        assertEquals("chunksOut", 1, masterStats.chunksOut.get());

        /*
         * Verify only one subtask started, that it is now ended, and that it
         * ended via an idle timeout.
         */
        assertEquals("subtaskStartCount", 1, masterStats.subtaskStartCount.get());
        assertEquals("subtaskEndCount", 1, masterStats.subtaskEndCount.get());
        assertEquals("subtaskIdleTimeout", 1, masterStats.subtaskIdleTimeoutCount.get());
        assertEquals("partitionCount", 1, masterStats.getMaximumPartitionCount());

        // close the master.
        masterBuffer.close();

        /*
         * await the future, but only for a little while in case the subtask
         * does not terminate correctly.
         */
        masterBuffer.getFuture().get(1, TimeUnit.SECONDS);

        /*
         * Note: These tests are the same as those before we close the master.
         * None of these asserts should be violated by closing the master.
         */
        
        // verify elements in/out; chunks in/out
        assertEquals("elementsIn", 2, masterStats.elementsIn.get());
        assertEquals("chunksIn", 2, masterStats.chunksIn.get());
        
        assertEquals("elementsOut", 2, masterStats.elementsOut.get());
        assertEquals("chunksOut", 1, masterStats.chunksOut.get());

        /*
         * Verify only one subtask started, that it is now ended, and that it
         * ended via an idle timeout.
         */
        assertEquals("subtaskStartCount", 1, masterStats.subtaskStartCount.get());
        assertEquals("subtaskEndCount", 1, masterStats.subtaskEndCount.get());
        assertEquals("subtaskIdleTimeout", 1, masterStats.subtaskIdleTimeoutCount.get());
        assertEquals("partitionCount", 1, masterStats.getMaximumPartitionCount());

    }
    
}
