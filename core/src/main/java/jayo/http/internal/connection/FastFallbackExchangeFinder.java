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

import jayo.JayoException;
import jayo.JayoInterruptedIOException;
import jayo.http.internal.connection.RoutePlanner.ConnectResult;
import jayo.http.internal.connection.RoutePlanner.Plan;
import jayo.scheduler.TaskRunner;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import static jayo.http.internal.Utils.JAYO_HTTP_NAME;

/**
 * Speculatively connects to each IP address of a target address, returning as soon as one of them
 * connects successfully. This kicks off new attempts every 250 ms until a connect succeeds.
 */
final class FastFallbackExchangeFinder implements ExchangeFinder {
    private final @NonNull RoutePlanner routePlanner;
    private final @NonNull TaskRunner taskRunner;

    private final long connectDelayNanos = Duration.ofMillis(250L).toNanos();
    private long nextTcpConnectAtNanos = Long.MIN_VALUE;

    /**
     * Plans currently being connected, and that will later be added to {@link #connectResults}. This is mutated by the
     * call thread only. It is accessed by background connect threads.
     */
    private final @NonNull CopyOnWriteArrayList<@NonNull Plan> tcpConnectsInFlight =
            new CopyOnWriteArrayList<>();

    /**
     * Results are posted here as they occur. The find job is done when either one plan completes successfully or all
     * plans fail.
     */
    private final @NonNull BlockingQueue<@NonNull ConnectResult> connectResults;

    FastFallbackExchangeFinder(final @NonNull RoutePlanner routePlanner, final @NonNull TaskRunner taskRunner) {
        assert routePlanner != null;
        assert taskRunner != null;

        this.routePlanner = routePlanner;
        this.taskRunner = taskRunner;

        this.connectResults = taskRunner.getBackend().decorate(new LinkedBlockingDeque<>());
    }

    @Override
    public @NonNull RealConnection find() {
        JayoException firstException = null;
        try {
            while (!tcpConnectsInFlight.isEmpty() || routePlanner.hasNext(null)) {
                if (routePlanner.isCanceled()) {
                    throw new JayoException("Canceled");
                }

                // Launch a new connection if we're ready to.
                long now = taskRunner.getBackend().nanoTime();
                long awaitTimeoutNanos = nextTcpConnectAtNanos - now;
                ConnectResult connectResult = null;
                if (tcpConnectsInFlight.isEmpty() || awaitTimeoutNanos <= 0) {
                    connectResult = launchTcpConnect();
                    nextTcpConnectAtNanos = now + connectDelayNanos;
                    awaitTimeoutNanos = connectDelayNanos;
                }

                // Wait for an in-flight connect to complete or fail.
                if (connectResult == null) {
                    connectResult = awaitTcpConnect(awaitTimeoutNanos);
                    if (connectResult == null) continue;
                }

                if (connectResult.isSuccess()) {
                    // We have a connected TCP connection. Cancel and defer the racing connects that all lost.
                    cancelInFlightConnects();

                    // Finish connecting. We won't have to if the winner is from the connection pool.
                    if (!connectResult.plan().isReady()) {
                        connectResult = connectResult.plan().connectTlsEtc();
                    }

                    if (connectResult.isSuccess()) {
                        return connectResult.plan().handleSuccess();
                    }
                }

                final var exception = connectResult.throwable();
                if (exception != null) {
                    if (exception instanceof JayoException jayoException) {
                        if (firstException == null) {
                            firstException = jayoException;
                        } else {
                            firstException.addSuppressed(jayoException);
                        }
                    } else if (exception instanceof IOException ioException) {
                        final var jayoException = JayoException.buildJayoException(ioException);
                        if (firstException == null) {
                            firstException = jayoException;
                        } else {
                            firstException.addSuppressed(jayoException);
                        }
                    } else if (exception instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    } else {
                        throw new RuntimeException(exception);
                    }
                }

                final var nextPlan = connectResult.nextPlan();
                if (nextPlan != null) {
                    // Try this plan's successor before deferred plans because it won the race!
                    routePlanner.getDeferredPlans().addFirst(nextPlan);
                }
            }
        } finally {
            cancelInFlightConnects();
        }

        assert firstException != null;
        throw firstException;
    }

    /**
     * @return non-null if we don't need to wait for the launched result. In such cases, this result must be processed
     * before whatever is waiting in the queue because we may have already acquired its connection.
     */
    private @Nullable ConnectResult launchTcpConnect() {
        final Plan plan;
        if (routePlanner.hasNext(null)) {
            Plan _plan; // mutable
            try {
                _plan = routePlanner.plan();
            } catch (Throwable e) {
                _plan = new FailedPlan(e);
            }
            plan = _plan;
        } else {
            return null; // Nothing further to try.
        }

        // Already connected. Return it immediately.
        if (plan.isReady()) {
            return new ConnectResult(plan, null, null);
        }

        // Already failed? Return it immediately.
        if (plan instanceof FailedPlan failedPlan) {
            return failedPlan.result;
        }

        // Connect TCP asynchronously.
        tcpConnectsInFlight.add(plan);
        taskRunner.execute(
                JAYO_HTTP_NAME + " connect " + routePlanner.getAddress().getUrl().redact(),
                true,
                () -> {
                    ConnectResult connectResult;
                    try {
                        connectResult = plan.connectTcp();
                    } catch (RuntimeException e) {
                        connectResult = new ConnectResult(plan, null, e);
                    }
                    // Only post a result if this hasn't since been canceled.
                    if (tcpConnectsInFlight.contains(plan)) {
                        try {
                            connectResults.put(connectResult);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt(); // Retain interrupted status.
                            cancelInFlightConnects(); // fixme should we do this ?
                            throw new JayoInterruptedIOException("current thread is interrupted");
                        }
                    }
                }
        );
        return null;
    }

    private ConnectResult awaitTcpConnect(final long timeoutNanos) {
        if (tcpConnectsInFlight.isEmpty()) {
            return null;
        }

        final ConnectResult connectResult;
        try {
            connectResult = connectResults.poll(timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Retain interrupted status.
            cancelInFlightConnects(); // fixme should we do this ?
            throw new JayoInterruptedIOException("current thread is interrupted");
        }
        if (connectResult == null) {
            return null;
        }

        tcpConnectsInFlight.remove(connectResult.plan());

        return connectResult;
    }

    private void cancelInFlightConnects() {
        for (final var plan : tcpConnectsInFlight) {
            plan.cancel();
            final var retry = plan.retry();
            if (retry == null) {
                continue;
            }
            routePlanner.getDeferredPlans().addLast(retry);
        }
        tcpConnectsInFlight.clear();
    }

    @Override
    public @NonNull RoutePlanner routePlanner() {
        return routePlanner;
    }
}
