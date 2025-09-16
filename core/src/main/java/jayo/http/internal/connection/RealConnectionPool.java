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
import jayo.JayoException;
import jayo.Socket;
import jayo.http.Address;
import jayo.http.ConnectionPool;
import jayo.http.JayoHttpClient;
import jayo.http.Route;
import jayo.http.internal.connection.RealCall.CallReference;
import jayo.scheduler.ScheduledTaskQueue;
import jayo.scheduler.TaskRunner;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

import static jayo.http.internal.Utils.JAYO_HTTP_NAME;

public final class RealConnectionPool implements ConnectionPool {
    private final @NonNull TaskRunner taskRunner;
    private final int maxIdleConnections;
    private final ExchangeFinder.@NonNull Factory exchangeFinderFactory;

    final long keepAliveDurationNs;

    private final @NonNull ScheduledTaskQueue cleanupQueue;

    /**
     * Holding the lock of the connection being added or removed when mutating this, and check its
     * {@link RealConnection#noNewExchanges} property. This defends against races where a connection is simultaneously
     * adopted and removed.
     */
    private final Collection<RealConnection> connections = new ConcurrentLinkedQueue<>();

    @SuppressWarnings("FieldMayBeFinal")
    private volatile @NonNull Map<@NonNull Address, @NonNull AddressState> addressStates = Map.of();
    // VarHandle mechanics
    private static final VarHandle ADDRESS_STATES;

    static {
        try {
            final var l = MethodHandles.lookup();
            ADDRESS_STATES = l.findVarHandle(RealConnectionPool.class, "addressStates", Map.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public RealConnectionPool(final int maxIdleConnections, final @NonNull Duration keepAlive) {
        this(JayoHttpClient.DEFAULT_TASK_RUNNER, maxIdleConnections, keepAlive, Duration.ofSeconds(10),
                Duration.ofSeconds(10), 10_000, true, true,
                new RouteDatabase());
    }

    RealConnectionPool(final @NonNull TaskRunner taskRunner,
                       final @NonNull Duration readTimeout,
                       final @NonNull Duration writeTimeout,
                       final int pingIntervalMillis,
                       final boolean retryOnConnectionFailure,
                       final boolean fastFallback,
                       final @NonNull RouteDatabase routeDatabase) {
        this(taskRunner, 5, Duration.ofMinutes(5), readTimeout, writeTimeout, pingIntervalMillis,
                retryOnConnectionFailure, fastFallback, routeDatabase);
    }

    /**
     * Create a new connection pool with tuning parameters appropriate for a single-user application. The tuning
     * parameters in this pool are subject to change in future Jayo HTTP releases.
     * <p>
     * Currently, this pool holds up to 5 idle connections which will be evicted after 5 minutes of inactivity.
     */
    private RealConnectionPool(final @NonNull TaskRunner taskRunner,
                               final int maxIdleConnections,
                               final @NonNull Duration keepAlive,
                               final @NonNull Duration readTimeout,
                               final @NonNull Duration writeTimeout,
                               final int pingIntervalMillis,
                               final boolean retryOnConnectionFailure,
                               final boolean fastFallback,
                               final @NonNull RouteDatabase routeDatabase) {
        this(taskRunner,
                maxIdleConnections,
                keepAlive,
                (pool, address, user) -> new FastFallbackExchangeFinder(
                        new ForceConnectRoutePlanner(
                                new RealRoutePlanner(
                                        taskRunner,
                                        pool,
                                        readTimeout,
                                        writeTimeout,
                                        pingIntervalMillis,
                                        retryOnConnectionFailure,
                                        fastFallback,
                                        address,
                                        routeDatabase,
                                        user)
                        ),
                        taskRunner));
    }

    RealConnectionPool(final @NonNull TaskRunner taskRunner,
                       final int maxIdleConnections,
                       final @NonNull Duration keepAlive,
                       final ExchangeFinder.@NonNull Factory exchangeFinderFactory) {
        assert taskRunner != null;
        assert keepAlive != null;
        assert exchangeFinderFactory != null;

        this.taskRunner = taskRunner;
        this.maxIdleConnections = maxIdleConnections;
        this.exchangeFinderFactory = exchangeFinderFactory;

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
     * Attempts to acquire a recycled connection to {@code address} for {@code connectionUser}. Returns the connection
     * if it was acquired, or null if no connection was acquired. The acquired connection will also be given to
     * {@code connectionUser} who may (for example) assign it to a {@link RealCall#connection}.
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
                                               final @NonNull ConnectionUser connectionUser,
                                               final @Nullable List<@NonNull Route> routes,
                                               final boolean requireMultiplexed) {
        assert address != null;
        assert connectionUser != null;

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

                connectionUser.acquireConnectionNoEvents(connection);
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
            final Socket toClose;
            connection.lock.lock();
            try {
//                noNewExchangesEvent = !connection.noNewExchanges;
                connection.noNewExchanges = true;
                toClose = connectionUser.releaseConnectionNoEvents();
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

    private static long jitterBy(final long backoffDelayMillis, final int amount) {
        return backoffDelayMillis + ThreadLocalRandom.current().nextInt(amount * -1, amount);
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
            scheduleOpener(connection.route().getAddress());
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
            final Socket toClose;
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

        for (final var policy : addressStates.values()) {
            scheduleOpener(policy);
        }
    }

    /**
     * Adds or replaces the policy for {@code address}.
     * This will trigger a background task to start creating connections as needed.
     */
    void setPolicy(final @NonNull Address address, final @NonNull AddressPolicy policy) {
        assert address != null;
        assert policy != null;

        final var newState = new AddressState(address, taskRunner.newScheduledQueue(), policy);
        final int newConnectionsNeeded;

        while (true) {
            Map<Address, AddressState> oldMap = this.addressStates;
            Map<Address, AddressState> newMap = new HashMap<>(oldMap);
            newMap.put(address, newState);
            if (ADDRESS_STATES.compareAndSet(this, oldMap, newMap)) {
                final var oldState = oldMap.get(address);
                final var oldPolicyMinimumConcurrentCalls =
                        (oldState != null) ? oldState.policy.minimumConcurrentCalls : 0;
                newConnectionsNeeded = policy.minimumConcurrentCalls - oldPolicyMinimumConcurrentCalls;
                break;
            }
        }

        if (newConnectionsNeeded > 0) {
            scheduleOpener(newState);
        } else if (newConnectionsNeeded < 0) {
            scheduleCloser();
        }
    }

    /**
     * Open connections to {@code address}, if required by the address policy.
     */
    void scheduleOpener(final @NonNull Address address) {
        assert address != null;

        final var addressState = addressStates.get(address);
        if (addressState != null) {
            scheduleOpener(addressState);
        }
    }

    private void scheduleOpener(final @NonNull AddressState addressState) {
        assert addressState != null;

        addressState.queue.schedule(JAYO_HTTP_NAME + " ConnectionPool connection opener", 0L,
                () -> openConnections(addressState));
    }

    /**
     * Ensure enough connections open to {@linkplain AddressState addressState}'s address to satisfy its
     * {@linkplain AddressPolicy Address policy}. If there are already enough connections, we're done.
     * If not, we create one and then schedule the task to run again immediately.
     */
    private long openConnections(final @NonNull AddressState addressState) {
        assert addressState != null;

        // This policy does not require minimum connections, don't run again
        if (addressState.policy.minimumConcurrentCalls == 0) {
            return -1L;
        }

        var concurrentCallCapacity = 0;
        for (final var connection : connections) {
            if (!addressState.address.equals(connection.route().getAddress())) {
                continue;
            }
            connection.lock.lock();
            try {
                concurrentCallCapacity += connection.allocationLimit;
            } finally {
                connection.lock.unlock();
            }

            // The policy was satisfied by existing connections, don't run again
            if (concurrentCallCapacity >= addressState.policy.minimumConcurrentCalls) {
                return -1L;
            }
        }

        // If we got here, then the policy was not satisfied -- open a connection!
        try {
            final var connection = exchangeFinderFactory
                    .newExchangeFinder(this, addressState.address, PoolConnectionUser.INSTANCE)
                    .find();

            // RealRoutePlanner will add the connection to the pool itself, other RoutePlanners may not
            // TODO: make all RoutePlanners consistent in this behavior
            if (!connections.contains(connection)) {
                connection.lock.lock();
                try {
                    put(connection);
                } finally {
                    connection.lock.unlock();
                }
            }

            return 0L; // run again immediately to create more connections if needed
        } catch (JayoException e) {
            // No need to log, user.connectFailed() will already have been called. Just try again later.
            return jitterBy(addressState.policy.backoffDelayMillis, addressState.policy.backoffJitterMillis) * 1000_000;
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
    long closeConnections(long now) {
        // Compute the concurrent call capacity for each address. We won't close a connection if doing so violates a
        // policy, unless it's OLD.
        final var addressStates = this.addressStates;
        for (AddressState state : addressStates.values()) {
            state.concurrentCallCapacity = 0;
        }
        for (final var connection : connections) {
            final var addressState = addressStates.get(connection.route().getAddress());
            if (addressState == null) {
                continue;
            }
            connection.lock.lock();
            try {
                addressState.concurrentCallCapacity += connection.allocationLimit;
            } finally {
                connection.lock.unlock();
            }
        }

        // Find the longest-idle connections in 2 categories:
        //
        //  1. OLD: Connections that have been idle for at least keepAliveDurationNs. We close these if we find them,
        //     regardless of what the address policies need.
        //
        //  2. EVICTABLE: Connections not required by any address policy. This matches connections that don't
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

                if (isEvictable(addressStates, connection)) {
                    evictableConnectionCount++;
                    if (idleAtNs < earliestEvictableIdleAtNs) {
                        earliestEvictableIdleAtNs = idleAtNs;
                        earliestEvictableConnection = connection;
                    }
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
            final var addressState = addressStates.get(toEvict.route().getAddress());
            if (addressState != null) {
                scheduleOpener(addressState);
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

    /**
     * @return true if no address policies prevent {@code connection} from being evicted.
     */
    private boolean isEvictable(final @NonNull Map<@NonNull Address, @NonNull AddressState> addressStates,
                                final @NonNull RealConnection connection) {
        assert addressStates != null;
        assert connection != null;

        final var addressState = addressStates.get(connection.route().getAddress());
        if (addressState == null) {
            return true;
        }

        final var capacityWithoutIt = addressState.concurrentCallCapacity - connection.allocationLimit;
        return capacityWithoutIt >= addressState.policy.minimumConcurrentCalls;
    }

    private static final class AddressState {
        private final @NonNull Address address;
        private final @NonNull ScheduledTaskQueue queue;
        private final @NonNull AddressPolicy policy;

        private AddressState(final @NonNull Address address,
                             final @NonNull ScheduledTaskQueue queue,
                             final @NonNull AddressPolicy policy) {
            assert address != null;
            assert queue != null;
            assert policy != null;

            this.address = address;
            this.queue = queue;
            this.policy = policy;
        }

        /**
         * How many calls the pool can carry without opening new connections. This field must only be accessed by the
         * connection closer task.
         */
        private int concurrentCallCapacity = 0;
    }
}
