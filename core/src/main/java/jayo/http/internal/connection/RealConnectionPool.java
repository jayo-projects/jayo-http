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

package jayo.http.internal.connection;

import jayo.Jayo;
import jayo.RawSocket;
import jayo.http.Address;
import jayo.http.ConnectionPool;
import jayo.http.Route;
import jayo.http.internal.Utils;
import jayo.http.internal.connection.RealCall.CallReference;
import jayo.scheduler.ScheduledTaskQueue;
import jayo.scheduler.TaskRunner;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static jayo.http.internal.Utils.JAYO_HTTP_NAME;

public final class RealConnectionPool implements ConnectionPool {
    /**
     * The maximum number of idle connections across all addresses.
     */
    private final int maxIdleConnections;

    final long keepAliveDurationNs;

    private final @NonNull ScheduledTaskQueue cleanupQueue;

    /**
     * Holding the lock of the connection being added or removed when mutating this, and check its
     * {@link RealConnection#noNewExchanges} property. This defends against races where a connection is simultaneously
     * adopted and removed.
     */
    private final Collection<RealConnection> connections = new ConcurrentLinkedQueue<>();

    public RealConnectionPool(final int maxIdleConnections, final @NonNull Duration keepAlive) {
        this(Utils.defaultTaskRunner(), maxIdleConnections, keepAlive);
    }

    RealConnectionPool(final @NonNull TaskRunner taskRunner,
                       final int maxIdleConnections,
                       final @NonNull Duration keepAlive) {
        assert taskRunner != null;
        assert keepAlive != null;

        this.maxIdleConnections = maxIdleConnections;

        this.keepAliveDurationNs = keepAlive.toNanos();
        this.cleanupQueue = taskRunner.newScheduledQueue();
    }
    // todo (maybe) : ConnectionListener

    @Override
    public int idleConnectionCount() {
        final var countAsLong = connections.stream()
                .filter(connection -> {
                    connection.lock.lock();
                    try {
                        return connection.calls.isEmpty();
                    } finally {
                        connection.lock.unlock();
                    }
                })
                .count();
        return Math.toIntExact(countAsLong);
    }

    @Override
    public int connectionCount() {
        return connections.size();
    }

    /**
     * Attempts to acquire a recycled connection to {@code address} for {@code call}. Returns the connection if it was
     * acquired, or null if no connection was acquired. The acquired connection will also be given to {@code call} who
     * may (for example) assign it to a {@link RealCall#connection}.
     * <p>
     * This confirms the returned connection is healthy before returning it. If this encounters any unhealthy
     * connections in its search, this will clean them up.
     * <p>
     * If {@code routes} is non-null these are the resolved routes (i.e., IP addresses) for the connection. This is used
     * to coalesce related domains to the same HTTP/2 connection, such as `my-org.com` and `my-org.ca`.
     */
    @Nullable
    RealConnection callAcquirePooledConnection(final boolean doExtensiveHealthChecks,
                                               final @NonNull Address address,
                                               final @NonNull RealCall call,
                                               final @Nullable List<@NonNull Route> routes,
                                               final boolean requireMultiplexed) {
        assert address != null;
        assert call != null;

        for (final var connection : connections) {
            // In the first lock-protected block, acquire the connection if it can satisfy this call.
            connection.lock.lock();
            try {
                if (requireMultiplexed && !connection.isMultiplexed()) {
                    continue;
                }
                if (!connection.isEligible(address, routes)) {
                    continue;
                }

                call.acquireConnectionNoEvents(connection);
            } finally {
                connection.lock.unlock();
            }

            // Confirm the connection is healthy and return it.
            if (connection.isHealthy(doExtensiveHealthChecks)) {
                return connection;
            }

            // In the second lock-protected block, release the unhealthy acquired connection. We're also on the hook to
            // close this connection if it's no longer in use.
//            final boolean noNewExchangesEvent;
            final RawSocket toClose;
            connection.lock.lock();
            try {
//                noNewExchangesEvent = !connection.noNewExchanges;
                connection.noNewExchanges = true;
                toClose = call.releaseConnectionNoEvents();
            } finally {
                connection.lock.unlock();
            }
            if (toClose != null) {
                Jayo.closeQuietly(toClose);
//                connectionListener.connectionClosed(connection);
//            } else if (noNewExchangesEvent) {
//                connectionListener.noNewExchanges(connection);
            }
        }
        return null;
    }

    public void put(final @NonNull RealConnection connection) {
        assert connection != null;

        connections.add(connection);
//    connection.queueEvent(() -> connectionListener.connectEnd(connection));
        scheduleCloser();
    }

    /**
     * Notify this pool that {@code connection} has become idle. Returns true if the connection has been removed from
     * the pool and should be closed.
     */
    boolean connectionBecameIdle(final @NonNull RealConnection connection) {
        assert connection != null;

        if (connection.noNewExchanges || maxIdleConnections == 0) {
            connection.noNewExchanges = true;
            connections.remove(connection);
            if (connections.isEmpty()) {
                cleanupQueue.cancelAll();
            }
            return true;
        } else {
            scheduleCloser();
            return false;
        }
    }

    @Override
    public void evictAll() {
        final var i = connections.iterator();
        while (i.hasNext()) {
            final var connection = i.next();
            final RawSocket toClose;
            connection.lock.lock();
            try {
                if (connection.calls.isEmpty()) {
                    i.remove();
                    connection.noNewExchanges = true;
                    toClose = connection.socket();
                } else {
                    toClose = null;
                }
            } finally {
                connection.lock.unlock();
            }
            if (toClose != null) {
                Jayo.closeQuietly(toClose);
//                connectionListener.connectionClosed(connection);
            }
        }

        if (connections.isEmpty()) {
            cleanupQueue.cancelAll();
        }
    }

    void scheduleCloser() {
        cleanupQueue.schedule(JAYO_HTTP_NAME + " ConnectionPool connection closer", 0L,
                () -> closeConnections(System.nanoTime()));
    }

    /**
     * Performs maintenance on this pool, evicting the connection that has been idle the longest if either it has
     * exceeded the keepalive limit or the idle connections limit.
     *
     * @return the duration in nanoseconds to sleep until the next scheduled call to this method. Returns -1 if no
     * further cleanups are required.
     */
    long closeConnections(final long now) {
        // Find the longest-idle connections in 2 categories:
        //
        //  1. OLD: Connections that have been idle for at least keepAliveDurationNs. We close these if we find them,
        //     regardless of what the address policies need.
        //
        //  2. EVICTABLE: Connections are not required by any address policy. This matches connections that don't
        //     participate in any policy, plus connections whose policies won't be violated if the connection is closed.
        //     We only close these if the idle connection limit is exceeded.
        //
        // Also count the evictable connections to find out if we must close an EVICTABLE connection before its
        // keepAliveDurationNs is reached.
        var earliestOldIdleAtNs = (now - keepAliveDurationNs) + 1;
        RealConnection earliestOldConnection = null;
        var earliestEvictableIdleAtNs = Long.MAX_VALUE;
        RealConnection earliestEvictableConnection = null;
        var inUseConnectionCount = 0;
        var evictableConnectionCount = 0;
        for (final var connection : connections) {
            connection.lock.lock();
            try {
                // If the connection is in use, keep searching.
                if (pruneAndGetAllocationCount(connection, now) > 0) {
                    inUseConnectionCount++;
                    continue;
                }

                long idleAtNs = connection.idleAtNs;

                if (idleAtNs < earliestOldIdleAtNs) {
                    earliestOldIdleAtNs = idleAtNs;
                    earliestOldConnection = connection;
                }

                evictableConnectionCount++;
                if (idleAtNs < earliestEvictableIdleAtNs) {
                    earliestEvictableIdleAtNs = idleAtNs;
                    earliestEvictableConnection = connection;
                }
            } finally {
                connection.lock.unlock();
            }
        }

        final RealConnection toEvict;
        final long toEvictIdleAtNs;

        // We had at least one OLD connection. Close the oldest one.
        if (earliestOldConnection != null) {
            toEvict = earliestOldConnection;
            toEvictIdleAtNs = earliestOldIdleAtNs;

            // We have too many EVICTABLE connections. Close the oldest one.
        } else if (evictableConnectionCount > maxIdleConnections) {
            toEvict = earliestEvictableConnection;
            toEvictIdleAtNs = earliestEvictableIdleAtNs;

        } else {
            toEvict = null;
            toEvictIdleAtNs = -1L;
        }

        if (toEvict != null) {
            // We've chosen a connection to evict. Confirm it's still okay to be evicted, then close it.
            toEvict.lock.lock();
            try {
                if (!toEvict.calls.isEmpty()) {
                    return 0L; // No longer idle.
                }
                if (toEvict.idleAtNs != toEvictIdleAtNs) {
                    return 0L; // No longer oldest.
                }
                toEvict.noNewExchanges = true;
                connections.remove(toEvict);
            } finally {
                toEvict.lock.unlock();
            }
            Jayo.closeQuietly(toEvict.socket());
            //connectionListener.connectionClosed(toEvict);
            if (connections.isEmpty()) {
                cleanupQueue.cancelAll();
            }

            // Clean up again immediately.
            return 0L;
        } else if (earliestEvictableConnection != null) {
            // A connection will be ready to evict soon.
            return earliestEvictableIdleAtNs + keepAliveDurationNs - now;
        } else if (inUseConnectionCount > 0) {
            // All connections are in use. It'll be at least the keep alive duration 'til we run again.
            return keepAliveDurationNs;
        } else {
            // No connections, idle or in use.
            return -1;
        }
    }

    /**
     * Prunes any leaked calls and then returns the number of remaining live calls on {@code connection}. Calls are
     * leaked if the connection is tracking them, but the application code has abandoned them. Leak detection is
     * imprecise and relies on garbage collection.
     */
    private int pruneAndGetAllocationCount(final @NonNull RealConnection connection, final long now) {
        assert connection != null;

        final var references = connection.calls;
        var i = 0;
        while (i < references.size()) {
            final var reference = references.get(i);

            if (reference.get() != null) {
                i++;
                continue;
            }

            // We've discovered a leaked call. This is an application bug.
            final var callReference = (CallReference) reference;
            LogCloseableUtils.logCloseableLeak(connection.route().getAddress().getUrl(), callReference.callStackTrace);

            references.remove(i);

            // If this was the last allocation, the connection is eligible for immediate eviction.
            if (references.isEmpty()) {
                connection.idleAtNs = now - keepAliveDurationNs;
                return 0;
            }
        }

        return references.size();
    }
}
