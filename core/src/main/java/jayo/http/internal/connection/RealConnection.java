/*
 * Copyright (c) 2025-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 *
 * Forked from OkHttp (https://github.com/square/okhttp), original copyright is below
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package jayo.http.internal.connection;

import jayo.*;
import jayo.http.*;
import jayo.http.http2.ErrorCode;
import jayo.http.http2.JayoConnectionShutdownException;
import jayo.http.http2.JayoStreamResetException;
import jayo.http.internal.JayoHostnameVerifier;
import jayo.http.internal.Utils;
import jayo.http.internal.http.ExchangeCodec;
import jayo.http.internal.http1.Http1ExchangeCodec;
import jayo.http.internal.http2.Http2Connection;
import jayo.http.internal.http2.Http2ExchangeCodec;
import jayo.http.internal.http2.Http2Stream;
import jayo.http.internal.http2.Settings;
import jayo.network.NetworkEndpoint;
import jayo.scheduler.TaskRunner;
import jayo.tls.Handshake;
import jayo.tls.JayoTlsPeerUnverifiedException;
import jayo.tls.Protocol;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.ref.Reference;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A connection to a remote web server capable of carrying 1 or more concurrent streams.
 * <p>
 * Connections are shared in a connection pool. Accesses to the connection's state must be guarded by holding a lock on
 * the connection.
 */
public final class RealConnection extends Http2Connection.Listener implements Connection, ExchangeCodec.Carrier {
    private final @NonNull TaskRunner taskRunner;
    private final @NonNull RealConnectionPool connectionPool;
    private final @NonNull Route route;
    /**
     * The low-level TCP endpoint.
     */
    private final @NonNull NetworkEndpoint rawEndpoint;
    /**
     * The application layer endpoint. Either a {@linkplain jayo.tls.TlsEndpoint TlsEndpoint} layered over
     * {@code rawEndpoint}, or {@code rawEndpoint} itself if this connection does not use SSL.
     */
    private final @NonNull Endpoint endpoint;
    private final @Nullable Handshake handshake;
    private final @NonNull Protocol protocol;
    private final int pingIntervalMillis;
    // todo (maybe) : ConnectionListener

    private @Nullable Http2Connection http2Connection = null;

    final @NonNull Lock lock = new ReentrantLock();
    // These properties are guarded by lock

    /**
     * If true, no new exchanges can be created on this connection. It is necessary to set this to true when removing a
     * connection from the pool; otherwise a racing caller might get it from the pool when it shouldn't. Symmetrically,
     * this must always be checked before returning a connection from the pool.
     * <p>
     * Once true, this is always true.
     */
    boolean noNewExchanges = false;

    /**
     * If true, this connection may not be used for coalesced requests. These are requests that could share the same
     * connection without sharing the same hostname.
     */
    private boolean noCoalescedConnections = false;

    /**
     * The number of times there was a problem establishing a stream that could be due to the route chosen.
     */
    int routeFailureCount = 0;

    private int successCount = 0;
    private int refusedStreamCount = 0;

    /**
     * The maximum number of concurrent streams that can be carried by this connection. If
     * {@code calls.size() < allocationLimit} then new streams can be created on this connection.
     */
    int allocationLimit = 1;

    /**
     * Current calls carried by this connection.
     */
    @NonNull
    List<@NonNull Reference<@NonNull RealCall>> calls = new ArrayList<>();

    /**
     * Timestamp when {@code calls.size()} reached zero. Also assigned upon initial connection.
     */
    long idleAtNs = Long.MAX_VALUE;

    RealConnection(final @NonNull TaskRunner taskRunner,
                   final @NonNull RealConnectionPool connectionPool,
                   final @NonNull Route route,
                   final @NonNull NetworkEndpoint rawEndpoint,
                   final @NonNull Endpoint endpoint,
                   final @Nullable Handshake handshake,
                   final @NonNull Protocol protocol,
                   final int pingIntervalMillis) {
        assert taskRunner != null;
        assert connectionPool != null;
        assert route != null;
        assert rawEndpoint != null;
        assert endpoint != null;
        assert protocol != null;
        assert pingIntervalMillis >= 0;

        this.taskRunner = taskRunner;
        this.connectionPool = connectionPool;
        this.route = route;
        this.rawEndpoint = rawEndpoint;
        this.endpoint = endpoint;
        this.handshake = handshake;
        this.protocol = protocol;
        this.pingIntervalMillis = pingIntervalMillis;
    }

    /**
     * @return true if this is an HTTP/2 connection. Such connections can be used in multiple HTTP requests
     * simultaneously.
     */
    boolean isMultiplexed() {
        return http2Connection != null;
    }

    @Override
    public void noNewExchanges() {
        lock.lock();
        try {
            noNewExchanges = true;
        } finally {
            lock.unlock();
        }
        // connectionListener.noNewExchanges(this)
    }

    /**
     * Prevent this connection from being used for hosts other than the one in {@link #route}.
     */
    void noCoalescedConnections() {
        lock.lock();
        try {
            noCoalescedConnections = true;
        } finally {
            lock.unlock();
        }
    }

    void incrementSuccessCount() {
        lock.lock();
        try {
            successCount++;
        } finally {
            lock.unlock();
        }
    }

    void start() {
        idleAtNs = System.nanoTime();
        if (protocol == Protocol.HTTP_2 || protocol == Protocol.H2_PRIOR_KNOWLEDGE) {
            startHttp2();
        }
    }

    private void startHttp2() {
        // HTTP/2 connection timeouts are set per-stream.
        rawEndpoint.setReadTimeout(Duration.ZERO);
        rawEndpoint.setWriteTimeout(Duration.ZERO);

        // todo (maybe) : flowControlListener from ConnectionListener
        final var http2Connection = new Http2Connection.Builder(true, taskRunner)
                .endpoint(endpoint, route.getAddress().getUrl().getHost())
                .listener(this)
                .pingIntervalMillis(pingIntervalMillis)
                .build();
        this.http2Connection = http2Connection;
        this.allocationLimit = Http2Connection.DEFAULT_SETTINGS.maxConcurrentStreams();
        http2Connection.start(true);
    }

    /**
     * @return true if this connection can carry a stream allocation to {@code address}. If non-null {@code route} is
     * the resolved route for a connection.
     */
    boolean isEligible(final @NonNull Address address, final @Nullable List<@NonNull Route> routes) {
        assert address != null;

        // If this connection is not accepting new exchanges, we're done.
        if (calls.size() >= allocationLimit || noNewExchanges) {
            return false;
        }

        // If the non-host fields of the address don't overlap, we're done.
        if (!((RealAddress) this.route.getAddress()).equalsNonHost(address)) {
            return false;
        }

        // If the host exactly matches, we're done: this connection can carry the address.
        if (address.getUrl().getHost().equals(this.route().getAddress().getUrl().getHost())) {
            return true; // This connection is a perfect match.
        }

        // At this point we don't have a hostname match. But we will still be able to carry the request if our
        // connection coalescing requirements are met. See also:
        // https://hpbn.co/optimizing-application-delivery/#eliminate-domain-sharding
        // https://daniel.haxx.se/blog/2016/08/18/http2-connection-coalescing/

        // 1. This connection must be HTTP/2.
        if (http2Connection == null) {
            return false;
        }

        // 2. The routes must share an IP address.
        if (routes == null || !routeMatchesAny(routes)) {
            return false;
        }

        // 3. This connection's server certificates must cover the new host.
        if (address.getHostnameVerifier() != JayoHostnameVerifier.INSTANCE) {
            return false;
        }
        if (!supportsUrl(address.getUrl())) {
            return false;
        }

        // 4. Certificate pinning must match the host.
        try {
            assert address.getCertificatePinner() != null;
            assert handshake != null;
            address.getCertificatePinner().check(address.getUrl().getHost(), handshake.getPeerCertificates());
        } catch (JayoTlsPeerUnverifiedException ignored) {
            return false;
        }

        return true; // This connection can carry the caller's address.
    }

    /**
     * Returns true if this connection's route has the same address as any of {@code candidates}. This requires us to
     * have a DNS address for both hosts, which only happens after route planning. We can't coalesce connections that
     * use a proxy, since proxies don't tell us the origin server's IP address.
     */
    private boolean routeMatchesAny(final @NonNull List<@NonNull Route> candidates) {
        assert candidates != null;

        return candidates.stream().anyMatch(candidate ->
                candidate.getAddress().getProxy() == null &&
                        route.getAddress().getProxy() == null &&
                        route.getSocketAddress().equals(candidate.getSocketAddress()));
    }

    private boolean supportsUrl(final @NonNull HttpUrl url) {
        assert url != null;

        final var routeUrl = route.getAddress().getUrl();

        if (url.getPort() != routeUrl.getPort()) {
            return false; // Port mismatch.
        }

        if (url.getHost().equals(routeUrl.getHost())) {
            return true; // Host match. The URL is supported.
        }

        // We have a host mismatch. But if the certificate matches, we're still good.
        return !noCoalescedConnections && handshake != null && certificateSupportHost(url, handshake);
    }

    private static boolean certificateSupportHost(final @NonNull HttpUrl url, final @Nullable Handshake handshake) {
        assert url != null;
        assert handshake != null;

        final var peerCertificates = handshake.getPeerCertificates();
        return !peerCertificates.isEmpty() &&
                JayoHostnameVerifier.verify(url.getHost(), (X509Certificate) peerCertificates.get(0));
    }

    @NonNull
    ExchangeCodec newCodec(final @NonNull JayoHttpClient client) {
        assert client != null;

        if (http2Connection != null) {
            // use HTTP/2
            return new Http2ExchangeCodec(client, this, http2Connection);
        }

        // use HTTP/1
        rawEndpoint.setReadTimeout(client.getReadTimeout());
        rawEndpoint.setWriteTimeout(client.getWriteTimeout());
        return new Http1ExchangeCodec(client, this, endpoint);
    }

    static final long IDLE_CONNECTION_HEALTHY_NS = 10_000_000_000L; // 10 seconds.

    /**
     * @return true if this connection is ready to host new streams.
     */
    public boolean isHealthy(final boolean doExtensiveChecks) {
        final var nowNs = System.nanoTime();

        if (!rawEndpoint.isOpen()) {
            return false;
        }

        if (http2Connection != null) {
            return http2Connection.isHealthy(nowNs);
        }

        if (doExtensiveChecks) {
            final long idleDurationNs;
            lock.lock();
            try {
                idleDurationNs = nowNs - idleAtNs;
            } finally {
                lock.unlock();
            }
            if (idleDurationNs >= IDLE_CONNECTION_HEALTHY_NS) {
                return isHealthy();
            }
        }

        return true;
    }

    /**
     * @return true if new reads and writes should be attempted on the underlying network socket of
     * {@link #rawEndpoint}.
     * @implNote Unfortunately, Java's networking APIs don't offer a good health check, so we go on our own by
     * attempting to read with a short timeout. If it fails immediately, we know the socket is unhealthy.
     */
    boolean isHealthy() {
        try {
            return Cancellable.call(Duration.ofMillis(1L), ignored -> {
                final var readTimeout = rawEndpoint.getReadTimeout();
                try {
                    rawEndpoint.setReadTimeout(Duration.ofMillis(1L));
                    return !endpoint.getReader().exhausted();
                } finally {
                    rawEndpoint.setReadTimeout(readTimeout);
                }
            });
        } catch (JayoTimeoutException e) {
            return true; // Read timed out; socket is good.
        } catch (JayoException e) {
            return false; // Couldn't read; socket is closed.
        }
    }

    /**
     * Refuse incoming streams.
     */
    @Override
    protected void onStream(final @NonNull Http2Stream stream) {
        assert stream != null;
        stream.close(ErrorCode.REFUSED_STREAM, null);
    }

    /**
     * When settings are received, adjust the allocation limit.
     */
    @Override
    protected void onSettings(final @NonNull Http2Connection connection, final @NonNull Settings settings) {
        assert connection != null;
        assert settings != null;

        lock.lock();
        try {
            var oldLimit = allocationLimit;
            allocationLimit = settings.maxConcurrentStreams();

            if (allocationLimit < oldLimit) {
                // We might need new connections to keep policies satisfied
                connectionPool.scheduleOpener(route.getAddress());
            } else if (allocationLimit > oldLimit) {
                // We might no longer need some connections
                connectionPool.scheduleCloser();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Track a failure using this connection. This may prevent both the connection and its route from
     * being used for future exchanges.
     */
    @Override
    public void trackFailure(final @NonNull RealCall call, final @Nullable JayoException e) {
        assert call != null;

//        boolean noNewExchangesEvent = false;
        lock.lock();
        try {
            if (e instanceof JayoStreamResetException jsre) {
                if (jsre.errorCode == ErrorCode.REFUSED_STREAM) {
                    // Stop using this connection on the 2nd REFUSED_STREAM error.
                    refusedStreamCount++;
                    if (refusedStreamCount > 1) {
//                        noNewExchangesEvent = !noNewExchanges;
                        noNewExchanges = true;
                        routeFailureCount++;
                    }
                } else if (jsre.errorCode == ErrorCode.CANCEL && call.isCanceled()) {
                    // Permit any number of CANCEL errors on locally canceled calls.
                } else {
                    // Everything else wants a fresh connection.
//                    noNewExchangesEvent = !noNewExchanges;
                    noNewExchanges = true;
                    routeFailureCount++;
                }
            } else if (!isMultiplexed() || e instanceof JayoConnectionShutdownException) {
//                noNewExchangesEvent = !noNewExchanges;
                noNewExchanges = true;

                // If this route hasn't completed a call, avoid it for new connections.
                if (successCount == 0) {
                    if (e != null) {
                        connectFailed(call.client, route);
                    }
                    routeFailureCount++;
                }
            }
        } finally {
            lock.unlock();
        }

//        if (noNewExchangesEvent) {
//            connectionListener.noNewExchanges(this);
//        }
    }

    /**
     * Track a bad route in the route database. Other routes will be attempted first.
     */
    private void connectFailed(final @NonNull RealJayoHttpClient client,
                               final @NonNull Route failedRoute) {
        assert client != null;
        assert route != null;

        client.routeDatabase.failed(failedRoute);
    }

    @Override
    public void cancel() {
        // Close the network endpoint so we don't end up doing synchronous I/O.
        Utils.closeQuietly(rawEndpoint);
    }

    @Override
    public @NonNull Route route() {
        return route;
    }

    @Override
    public @Nullable Handshake handshake() {
        return handshake;
    }

    @Override
    public @NonNull Protocol protocol() {
        return protocol;
    }

    @Override
    public @NonNull Endpoint endpoint() {
        return endpoint;
    }

    public static @NonNull RealConnection newTestConnection(final @NonNull TaskRunner taskRunner,
                                                            final @NonNull RealConnectionPool connectionPool,
                                                            final @NonNull Route route,
                                                            final @NonNull NetworkEndpoint networkEndpoint,
                                                            final long idleAtNs) {
        final var endpoint = new Endpoint() {
            @Override
            public @NonNull Reader getReader() {
                return Buffer.create();
            }

            @Override
            public @NonNull Writer getWriter() {
                return Buffer.create();
            }

            @Override
            public void close() {
            }

            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public @NonNull Object getUnderlying() {
                return networkEndpoint;
            }
        };

        final var result = new RealConnection(
                taskRunner,
                connectionPool,
                route,
                networkEndpoint,
                endpoint,
                null,
                Protocol.HTTP_2,
                0
        );
        result.idleAtNs = idleAtNs;
        return result;
    }
}
