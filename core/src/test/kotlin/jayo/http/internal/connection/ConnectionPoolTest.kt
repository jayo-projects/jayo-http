/*
 * Copyright (c) 2025-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 *
 * Forked from OkHttp (https://github.com/square/okhttp), original copyright is below
 *
 * Copyright (C) 2013 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jayo.http.internal.connection

import jayo.http.ClientRequest
import jayo.http.ConnectionPool
import jayo.http.JayoHttpClient
import jayo.http.internal.FakeRoutePlanner
import jayo.http.internal.TestUtils.awaitGarbageCollection
import jayo.http.internal.http2.MockHttp2Peer
import jayo.scheduler.TaskRunner
import jayo.scheduler.internal.activeQueues
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.withLock

class ConnectionPoolTest {
    private val routePlanner = FakeRoutePlanner()
    private val factory = routePlanner.factory
    private val peer = MockHttp2Peer()

    /** The fake task runner prevents the cleanup runnable from being started.  */
    private val addressA = factory.newAddress("a")
    private val routeA1 = factory.newRoute(addressA)
    private val addressB = factory.newAddress("b")
    private val routeB1 = factory.newRoute(addressB)
    private val addressC = factory.newAddress("c")
    private val routeC1 = factory.newRoute(addressC)

    @AfterEach
    fun tearDown() {
        factory.close()
        peer.close()
    }

    @Test
    fun connectionsEvictedWhenIdleLongEnough() {
        val pool = factory.newConnectionPool()
        val c1 = factory.newConnection(pool, routeA1, 50L)

        // Running at time 50, the pool returns that nothing can be evicted until time 150.
        assertThat(pool.closeConnections(50L)).isEqualTo(100L)
        assertThat(pool.connectionCount()).isEqualTo(1)
        assertThat(c1.socket().isOpen).isTrue()

        // Running at time 60, the pool returns that nothing can be evicted until time 150.
        assertThat(pool.closeConnections(60L)).isEqualTo(90L)
        assertThat(pool.connectionCount()).isEqualTo(1)
        assertThat(c1.socket().isOpen).isTrue()

        // Running at time 149, the pool returns that nothing can be evicted until time 150.
        assertThat(pool.closeConnections(149L)).isEqualTo(1L)
        assertThat(pool.connectionCount()).isEqualTo(1)
        assertThat(c1.socket().isOpen).isTrue()

        // Running at time 150, the pool evicts.
        assertThat(pool.closeConnections(150L)).isEqualTo(0)
        assertThat(pool.connectionCount()).isEqualTo(0)
        assertThat(c1.socket().isOpen).isFalse()

        // Running again, the pool reports that no further runs are necessary.
        assertThat(pool.closeConnections(150L)).isEqualTo(-1)
        assertThat(pool.connectionCount()).isEqualTo(0)
        assertThat(c1.socket().isOpen).isFalse()
    }

    @Test
    fun inUseConnectionsNotEvicted() {
        val pool = factory.newConnectionPool()
        val c1 = factory.newConnection(pool, routeA1, 50L)
        val client = JayoHttpClient.builder()
            .connectionPool(pool)
            .build()
        val call = client.newCall(ClientRequest.get(addressA.url)) as RealCall
        call.enterNetworkInterceptorExchange(call.request(), true)
        c1.lock.withLock { call.acquireConnectionNoEvents(c1) }

        // Running at time 50, the pool returns that nothing can be evicted until time 150.
        assertThat(pool.closeConnections(50L)).isEqualTo(100L)
        assertThat(pool.connectionCount()).isEqualTo(1)
        assertThat(c1.socket().isOpen).isTrue()

        // Running at time 60, the pool returns that nothing can be evicted until time 160.
        assertThat(pool.closeConnections(60L)).isEqualTo(100L)
        assertThat(pool.connectionCount()).isEqualTo(1)
        assertThat(c1.socket().isOpen).isTrue()

        // Running at time 160, the pool returns that nothing can be evicted until time 260.
        assertThat(pool.closeConnections(160L)).isEqualTo(100L)
        assertThat(pool.connectionCount()).isEqualTo(1)
        assertThat(c1.socket().isOpen).isTrue()
    }

    @Test
    fun cleanupPrioritizesEarliestEviction() {
        val pool = factory.newConnectionPool()
        val c1 = factory.newConnection(pool, routeA1, 75L)
        val c2 = factory.newConnection(pool, routeB1, 50L)

        // Running at time 75, the pool returns that nothing can be evicted until time 150.
        assertThat(pool.closeConnections(75L)).isEqualTo(75L)
        assertThat(pool.connectionCount()).isEqualTo(2)

        // Running at time 149, the pool returns that nothing can be evicted until time 150.
        assertThat(pool.closeConnections(149L)).isEqualTo(1L)
        assertThat(pool.connectionCount()).isEqualTo(2)

        // Running at time 150, the pool evicts c2.
        assertThat(pool.closeConnections(150L)).isEqualTo(0L)
        assertThat(pool.connectionCount()).isEqualTo(1)
        assertThat(c1.socket().isOpen).isTrue()
        assertThat(c2.socket().isOpen).isFalse()

        // Running at time 150, the pool returns that nothing can be evicted until time 175.
        assertThat(pool.closeConnections(150L)).isEqualTo(25L)
        assertThat(pool.connectionCount()).isEqualTo(1)

        // Running at time 175, the pool evicts c1.
        assertThat(pool.closeConnections(175L)).isEqualTo(0L)
        assertThat(pool.connectionCount()).isEqualTo(0)
        assertThat(c1.socket().isOpen).isFalse()
        assertThat(c2.socket().isOpen).isFalse()
    }

    @Test
    fun oldestConnectionsEvictedIfIdleLimitExceeded() {
        val pool =
            factory.newConnectionPool(
                maxIdleConnections = 2,
            )
        val c1 = factory.newConnection(pool, routeA1, 50L)
        val c2 = factory.newConnection(pool, routeB1, 75L)

        // With 2 connections, there's no need to evict until the connections time out.
        assertThat(pool.closeConnections(100L)).isEqualTo(50L)
        assertThat(pool.connectionCount()).isEqualTo(2)
        assertThat(c1.socket().isOpen).isTrue()
        assertThat(c2.socket().isOpen).isTrue()

        // Add a third connection
        val c3 = factory.newConnection(pool, routeC1, 75L)

        // The third connection bounces the first.
        assertThat(pool.closeConnections(100L)).isEqualTo(0L)
        assertThat(pool.connectionCount()).isEqualTo(2)
        assertThat(c1.socket().isOpen).isFalse()
        assertThat(c2.socket().isOpen).isTrue()
        assertThat(c3.socket().isOpen).isTrue()
    }

    @Test
    fun leakedAllocation() {
        val pool = factory.newConnectionPool()
        val c1 = factory.newConnection(pool, routeA1, 0L)
        allocateAndLeakAllocation(pool, c1)
        awaitGarbageCollection()
        assertThat(pool.closeConnections(100L)).isEqualTo(0L)
        assertThat(c1.calls).isEmpty()

        // Can't allocate once a leak has been detected.
        assertThat(c1.noNewExchanges).isTrue()
    }

    @Test
    fun interruptStopsThread() {
        val taskRunnerThreads = mutableListOf<Thread>()
        val taskRunner = TaskRunner.create(
            ThreadPoolExecutor(
                // corePoolSize:
                0,
                // maximumPoolSize:
                Int.MAX_VALUE,
                // keepAliveTime:
                60L,
                TimeUnit.SECONDS,
                SynchronousQueue(),
            ) { runnable ->
                Thread(runnable, "interruptStopsThread TaskRunner")
                    .also { taskRunnerThreads += it }
            }
        )

        val pool =
            factory.newConnectionPool(
                taskRunner = taskRunner,
                maxIdleConnections = 2,
            )
        factory.newConnection(pool, routeA1)
        // Racy causing flaky tests
        // assertThat(taskRunner.activeQueues()).isNotEmpty()
        assertThat(taskRunnerThreads).isNotEmpty()
        Thread.sleep(100)
        for (t in taskRunnerThreads) {
            t.interrupt()
        }
        Thread.sleep(100)
        assertThat(taskRunner.activeQueues()).isEmpty()
    }

    /** Use a helper method so there's no hidden reference remaining on the stack.  */
    private fun allocateAndLeakAllocation(
        pool: ConnectionPool,
        connection: RealConnection,
    ) {
        val client = JayoHttpClient.builder()
            .connectionPool(pool)
            .build()
        val call = client.newCall(ClientRequest.get(connection.route().address.url)) as RealCall
        call.enterNetworkInterceptorExchange(call.request(), true)
        connection.lock.withLock { call.acquireConnectionNoEvents(connection) }
    }
}
