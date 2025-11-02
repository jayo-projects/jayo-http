/*
 * Copyright (c) 2025-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 *
 * Forked from OkHttp (https://github.com/square/okhttp), original copyright is below
 *
 * Copyright (C) 2022 Block, Inc.
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
import jayo.JayoProtocolException;
import jayo.Socket;
import jayo.http.CertificatePinner;
import jayo.http.ClientRequest;
import jayo.http.ConnectionSpec;
import jayo.http.Route;
import jayo.http.internal.JayoHostnameVerifier;
import jayo.http.internal.RealCertificatePinner;
import jayo.http.internal.RealConnectionSpec;
import jayo.http.internal.connection.RoutePlanner.ConnectResult;
import jayo.http.internal.connection.RoutePlanner.Plan;
import jayo.http.internal.http.ExchangeCodec;
import jayo.http.internal.http1.Http1ExchangeCodec;
import jayo.network.*;
import jayo.scheduler.TaskRunner;
import jayo.tls.*;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;

import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_PROXY_AUTH;
import static jayo.http.internal.UrlUtils.toHostHeader;
import static jayo.tools.JayoTlsUtils.createHandshake;

/**
 * A single attempt to connect to a remote server, including these steps:
 * <ul>
 * <li>{@linkplain #connectNetworkSocket() TCP handshake}.
 * <li>Optional {@linkplain #connectTunnel() CONNECT tunnels}. When using an HTTP proxy to reach an HTTPS server, we
 * must send a {@code CONNECT} request, and handle authorization challenges from the proxy.
 * <li>Optional {@linkplain #connectTls(ClientTlsSocket.Parameterizer) TLS handshake}.
 * </ul>
 * Each step may fail. If a retry is possible, a new instance is created with the next plan, which will be configured
 * differently.
 */
public final class ConnectPlan implements Plan, ExchangeCodec.Carrier {
    private static final int MAX_TUNNEL_ATTEMPTS = 21;

    private final @NonNull TaskRunner taskRunner;
    private final @NonNull RealConnectionPool connectionPool;
    private final @NonNull Duration readTimeout;
    private final @NonNull Duration writeTimeout;
    private final @NonNull Duration connectTimeout;
    private final @NonNull Duration pingInterval;
    private final boolean retryOnConnectionFailure;
    private final @NonNull RealCall call;
    private final @NonNull RealRoutePlanner routePlanner;
    // Specifics to this plan.
    private final @NonNull Route route;
    final @Nullable List<@NonNull Route> routes;
    private final int attempt;
    private final @Nullable ClientRequest tunnelRequest;
    final int connectionSpecIndex;
    final boolean isTlsFallback;

    /**
     * True if this connect was canceled; typically because it lost a race.
     */
    private volatile boolean canceled = false;

    // These properties are initialized by connect() and never reassigned.

    /**
     * The low-level TCP socket.
     */
    private /* lateinit */ NetworkSocket.Unconnected rawSocket = null;
    /**
     * The connected TCP socket.
     */
    private /* lateinit */ NetworkSocket networkSocket = null;
    /**
     * The application layer socket. Either a {@link TlsSocket} layered over {@link #networkSocket}, or
     * {@link #networkSocket} itself if this connection does not use SSL.
     */
    private /* lateinit */ Socket socket = null;
    private @Nullable Handshake handshake = null;
    private @Nullable Protocol protocol = null;
    private @Nullable RealConnection connection = null;

    ConnectPlan(final @NonNull TaskRunner taskRunner,
                final @NonNull RealConnectionPool connectionPool,
                final @NonNull Duration readTimeout,
                final @NonNull Duration writeTimeout,
                final @NonNull Duration connectTimeout,
                final @NonNull Duration pingInterval,
                final boolean retryOnConnectionFailure,
                final @NonNull RealCall call,
                final @NonNull RealRoutePlanner routePlanner,
                final @NonNull Route route,
                final @Nullable List<@NonNull Route> routes,
                final int attempt,
                final @Nullable ClientRequest tunnelRequest,
                final int connectionSpecIndex,
                final boolean isTlsFallback) {
        assert taskRunner != null;
        assert connectionPool != null;
        assert readTimeout != null;
        assert writeTimeout != null;
        assert connectTimeout != null;
        assert pingInterval != null;
        assert call != null;
        assert routePlanner != null;
        assert route != null;
        assert attempt >= 0;

        this.taskRunner = taskRunner;
        this.connectionPool = connectionPool;
        this.readTimeout = readTimeout;
        this.writeTimeout = writeTimeout;
        this.connectTimeout = connectTimeout;
        this.pingInterval = pingInterval;
        this.retryOnConnectionFailure = retryOnConnectionFailure;
        this.call = call;
        this.routePlanner = routePlanner;
        this.route = route;
        this.routes = routes;
        this.attempt = attempt;
        this.tunnelRequest = tunnelRequest;
        this.connectionSpecIndex = connectionSpecIndex;
        this.isTlsFallback = isTlsFallback;
    }

    /**
     * @return true if this connection is ready for use, including TCP, tunnels, and TLS.
     */
    @Override
    public boolean isReady() {
        return protocol != null;
    }

    private @NonNull ConnectPlan copy(final int attempt,
                                      final @Nullable ClientRequest tunnelRequest,
                                      final int connectionSpecIndex,
                                      final boolean isTlsFallback) {
        return new ConnectPlan(taskRunner,
                connectionPool,
                readTimeout,
                writeTimeout,
                connectTimeout,
                pingInterval,
                retryOnConnectionFailure,
                call,
                routePlanner,
                route,
                routes,
                attempt,
                tunnelRequest,
                connectionSpecIndex,
                isTlsFallback);
    }

    @Override
    public @NonNull ConnectResult connectTcp() {
        if (networkSocket != null) {
            throw new IllegalStateException("TCP already connected");
        }

        var success = false;
        // Tell the call about the connecting call so async cancels work.
        call.plansToCancel.add(this);
        try {
            call.eventListener.connectStart(call, route.getSocketAddress(), route.getAddress().getProxy());
//            connectionPool.connectionListener.connectStart(route, call)

            connectNetworkSocket();
            success = true;
            return new ConnectResult(this, null, null);
        } catch (JayoException je) {
            call.eventListener.connectFailed(call, route.getSocketAddress(), route.getAddress().getProxy(), null, je);
//            connectionPool.connectionListener().connectFailed(route, call, je);
            return new ConnectResult(this, null, je);
        } finally {
            call.plansToCancel.remove(this);
            if (!success && networkSocket != null) {
                Jayo.closeQuietly(networkSocket);
            }
        }
    }

    /**
     * Does all the work necessary to build a full HTTP or HTTPS connection on a raw network socket through its
     * underlying socket.
     */
    private void connectNetworkSocket() {
        final var rawSocket = NetworkSocket.builder()
                .connectTimeout(connectTimeout)
                .openTcp();
        this.rawSocket = rawSocket;

        // Handle the race where cancel() precedes connectNetworkSocket(). We don't want to miss a cancel.
        if (canceled) {
            throw new JayoException("canceled");
        }

        final NetworkSocket networkSocket;
        try {
            if (route.getAddress().getProxy() instanceof Proxy.Socks socksProxy) {
                networkSocket = rawSocket.connect(route.getSocketAddress(), socksProxy);
            } else {
                networkSocket = rawSocket.connect(route.getSocketAddress());
            }
        } catch (JayoException e) {
            final var connectException = new JayoConnectException("Failed to connect to " + route.getSocketAddress());
            connectException.addSuppressed(e);
            throw connectException;
        }
        this.networkSocket = networkSocket;
        this.socket = networkSocket;
    }

    @Override
    public @NonNull ConnectResult connectTlsEtc() {
        if (networkSocket == null) {
            throw new IllegalStateException("TCP not connected");
        }
        final var _rawSocket = networkSocket;
        if (isReady()) {
            throw new IllegalStateException("already connected");
        }

        final var connectionSpecs = route.getAddress().getConnectionSpecs();
        ConnectPlan retryTlsConnection = null;
        var success = false;

        // Tell the call about the connecting call so async cancels work.
        call.plansToCancel.add(this);
        try {
            if (tunnelRequest != null) {
                final var tunnelResult = connectTunnel();

                // Tunnel didn't work. Start it all again.
                if (tunnelResult.nextPlan() != null || tunnelResult.throwable() != null) {
                    return tunnelResult;
                }
            }

            if (route.getAddress().getClientTlsSocketBuilder() != null) {
                // Assume the server won't send a TLS ServerHello until we send a TLS ClientHello. If that happens, then
                // we will have buffered bytes that are needed by the SSLSocket!
                // This check is imperfect: it doesn't tell us whether a handshake will succeed, just that it will
                // almost certainly fail because the proxy has sent unexpected data.
                if (_rawSocket.getReader().bytesAvailable() > 0L) {
                    throw new JayoException("TLS tunnel buffered too many bytes!");
                }

                call.eventListener.secureConnectStart(call);

                // Create the wrapper over the connected socket.
                final var tlsParameterizer = route.getAddress().getClientTlsSocketBuilder().createParameterizer(
                        _rawSocket,
                        route.getAddress().getUrl().getHost(),
                        route.getAddress().getUrl().getPort());

                final var tlsEquipPlan = planWithCurrentOrInitialConnectionSpec(connectionSpecs, tlsParameterizer);
                final var connectionSpec = connectionSpecs.get(tlsEquipPlan.connectionSpecIndex);

                // Figure out the next connection spec in case we need a retry.
                retryTlsConnection = tlsEquipPlan.nextConnectionSpec(connectionSpecs, tlsParameterizer);

                ((RealConnectionSpec) connectionSpec)
                        .apply(tlsParameterizer, tlsEquipPlan.isTlsFallback, route.getAddress().getProtocols());
                connectTls(tlsParameterizer);
                call.eventListener.secureConnectEnd(call, handshake);
            } else {
                protocol = route.getAddress().getProtocols().contains(Protocol.H2_PRIOR_KNOWLEDGE)
                        ? Protocol.H2_PRIOR_KNOWLEDGE
                        : Protocol.HTTP_1_1;
            }

            assert socket != null;
            assert protocol != null;
            final var connection = new RealConnection(
                    taskRunner,
                    route,
                    _rawSocket,
                    socket,
                    handshake,
                    protocol,
                    pingInterval
            );
            this.connection = connection;
            connection.start();

            // Success.
            call.eventListener.connectEnd(call, route.getSocketAddress(), route.getAddress().getProxy(), protocol);
            success = true;
            return new ConnectResult(this, null, null);
        } catch (JayoException je) {
            call.eventListener.connectFailed(call, route.getSocketAddress(), route.getAddress().getProxy(), null, je);
//            connectionPool.connectionListener.connectFailed(route, call, je);

            if (!retryOnConnectionFailure || !retryTlsHandshake(je)) {
                retryTlsConnection = null;
            }

            return new ConnectResult(this, retryTlsConnection, je);
        } finally {
            call.plansToCancel.remove(this);
            if (!success) {
                if (socket != null) {
                    Jayo.closeQuietly(socket);
                }
                Jayo.closeQuietly(_rawSocket);
            }
        }
    }

    /**
     * @return this if its {@code connectionSpecIndex} is defined, or a new connection with it defined otherwise
     */
    @NonNull
    ConnectPlan planWithCurrentOrInitialConnectionSpec(
            final @NonNull List<@NonNull ConnectionSpec> connectionSpecs,
            final ClientTlsSocket.@NonNull Parameterizer tlsParameterizer) {
        assert connectionSpecs != null;
        assert tlsParameterizer != null;

        if (connectionSpecIndex != -1) {
            return this;
        }
        final var nextConnectionSpec = nextConnectionSpec(connectionSpecs, tlsParameterizer);
        if (nextConnectionSpec == null) {
            throw new JayoUnknownServiceException(
                    "Unable to find acceptable protocols." +
                            " isFallback=" + isTlsFallback +
                            ", modes=" + connectionSpecs +
                            ", supported protocols=" + tlsParameterizer.getEnabledProtocols());
        }
        return nextConnectionSpec;
    }

    /**
     * @return a copy of this connection with the next connection spec to try, or null if no other compatible connection
     * specs are available.
     */
    @Nullable
    ConnectPlan nextConnectionSpec(final @NonNull List<@NonNull ConnectionSpec> connectionSpecs,
                                   final ClientTlsSocket.@NonNull Parameterizer tlsParameterizer) {
        assert connectionSpecs != null;
        assert tlsParameterizer != null;

        for (var i = connectionSpecIndex + 1; i < connectionSpecs.size(); i++) {
            if (connectionSpecs.get(i).isCompatible(tlsParameterizer)) {
                return copy(this.attempt, this.tunnelRequest, i, (connectionSpecIndex != -1));
            }
        }
        return null;
    }

    private void connectTls(final ClientTlsSocket.@NonNull Parameterizer tlsParameterizer) {
        assert tlsParameterizer != null;

        // Force handshake and block for the TLS session establishment.
        final var tlsSocket = tlsParameterizer.build();

        final var address = route.getAddress();
        var success = false;
        try {
            final var tlsSession = tlsSocket.getSession();
            final var unverifiedHandshake = tlsSocket.getHandshake();

            // Verify that the socket's certificates are acceptable for the target host.
            assert address.getHostnameVerifier() != null;
            if (!address.getHostnameVerifier().verify(address.getUrl().getHost(), tlsSession)) {
                final var peerCertificates = unverifiedHandshake.getPeerCertificates();
                if (!peerCertificates.isEmpty()) {
                    final var cert = (X509Certificate) peerCertificates.get(0);
                    throw new JayoTlsPeerUnverifiedException(
                            "Hostname " + address.getUrl().getHost() + " not verified:\n" +
                                    "certificate: " + CertificatePinner.pin(cert) + "\n" +
                                    "DN: " + cert.getSubjectX500Principal().getName() + "\n" +
                                    "subjectAltNames: " + JayoHostnameVerifier.INSTANCE.allSubjectAltNames(cert));
                } else {
                    throw new JayoTlsPeerUnverifiedException(
                            "Hostname " + address.getUrl().getHost() + " not verified (no certificates)");
                }
            }

            final var certificatePinner = (RealCertificatePinner) address.getCertificatePinner();
            assert certificatePinner != null;

            final var handshake = createHandshake(
                    unverifiedHandshake.getProtocol(),
                    unverifiedHandshake.getTlsVersion(),
                    unverifiedHandshake.getCipherSuite(),
                    unverifiedHandshake.getLocalCertificates(),
                    () -> {
                        assert certificatePinner.getCertificateChainCleaner() != null;
                        return certificatePinner.getCertificateChainCleaner().clean(
                                unverifiedHandshake.getPeerCertificates());
                    });
            this.handshake = handshake;

            // Check that the certificate pinner is satisfied by the certificates presented.
            certificatePinner.check(address.getUrl().getHost(), () ->
                    handshake.getPeerCertificates().stream()
                            .map(cert -> (X509Certificate) cert)
                            .toList());

            // Success! Save the handshake and the ALPN protocol.
            socket = tlsSocket;
            protocol = handshake.getProtocol();
            success = true;
        } finally {
            if (!success) {
                Jayo.closeQuietly(tlsSocket);
            }
        }
    }

    /**
     * Does all the work to build an HTTPS connection over a proxy tunnel. The catch here is that a proxy server can
     * issue an auth challenge and then close the connection.
     *
     * @return the next plan to attempt, or null if no further attempt should be made either because we've successfully
     * connected or because no further attempts should be made.
     * @throws JayoProtocolException if max tunnel attempts are reached.
     */
    @NonNull
    ConnectResult connectTunnel() {
        final var nextTunnelRequest = createTunnel();
        if (nextTunnelRequest == null) {
            return new ConnectResult(this, null, null); // Success.
        }

        // The proxy decided to close the connection after an auth challenge. Retry with different auth credentials.
        if (networkSocket != null) {
            Jayo.closeQuietly(networkSocket);
        }

        final var nextAttempt = attempt + 1;
        if (nextAttempt < MAX_TUNNEL_ATTEMPTS) {
            call.eventListener.connectEnd(call, route.getSocketAddress(), route.getAddress().getProxy(), null);
            return new ConnectResult(this,
                    copy(nextAttempt, nextTunnelRequest, connectionSpecIndex, isTlsFallback),
                    null);
        } else {
            final var failure =
                    new JayoProtocolException("Too many tunnel connections attempted: " + MAX_TUNNEL_ATTEMPTS);
            call.eventListener().connectFailed(call, route.getSocketAddress(), route.getAddress().getProxy(), null, failure);
//            connectionPool.connectionListener.connectFailed(route, call, failure);
            return new ConnectResult(this, null, failure);
        }
    }

    /**
     * To make an HTTPS connection over an HTTP proxy, send an unencrypted CONNECT request to create the proxy
     * connection. This may need to be retried if the proxy requires authorization.
     */
    private @Nullable ClientRequest createTunnel() {
        assert tunnelRequest != null;
        var nextRequest = tunnelRequest;
        // Make a TLS tunnel on the first message pair of each TLS + proxy connection.
        final var url = route.getAddress().getUrl();
        final var requestLine = "CONNECT " + toHostHeader(url, true) + " HTTP/1.1";
        while (true) {
            final var tunnelCodec = new Http1ExchangeCodec(
                    // No client for CONNECT tunnels
                    null,
                    this,
                    socket);
            networkSocket.setReadTimeout(readTimeout);
            networkSocket.setWriteTimeout(writeTimeout);
            tunnelCodec.writeRequest(nextRequest.getHeaders(), requestLine);
            tunnelCodec.finishRequest();
            final var responseBuilder = tunnelCodec.readResponseHeaders(false);
            assert responseBuilder != null;
            final var response = responseBuilder.request(nextRequest).build();
            tunnelCodec.skipConnectBody(response);

            switch (response.getStatusCode()) {
                case HTTP_OK -> {
                    return null;
                }

                case HTTP_PROXY_AUTH -> {
                    nextRequest = route.getAddress().getProxyAuthenticator().authenticate(route, response);
                    if (nextRequest == null) {
                        throw new JayoException("Failed to authenticate with proxy");
                    }

                    if ("close".equalsIgnoreCase(response.header("Connection"))) {
                        return nextRequest;
                    }
                }

                default ->
                        throw new JayoException("Unexpected response code for CONNECT: " + response.getStatusCode());
            }
        }
    }

    /**
     * @return the connection to use, which might be different from {@link #connection}.
     */
    @Override
    public @NonNull RealConnection handleSuccess() {
        call.client.routeDatabase.connected(route);

        assert connection != null;
        final var connection = this.connection;
//        connection.connectionListener.connectEnd(connection, route, call)

        // If we raced another call connecting to this host, coalesce the connections. This makes for 3 different
        // lookups in the connection pool!
        final var pooled3 = routePlanner.planReusePooledConnection(this, routes);
        if (pooled3 != null) {
            return pooled3.connection();
        }

        connection.lock.lock();
        try {
            connectionPool.put(connection);
            call.acquireConnectionNoEvents(connection);
        } finally {
            connection.lock.unlock();
        }

        call.eventListener.connectionAcquired(call, connection);
//        connection.connectionListener.connectionAcquired(connection, call);
        return connection;
    }

    @Override
    public @NonNull Route route() {
        return route;
    }

    @Override
    public void trackFailure(final @NonNull RealCall call, final @Nullable JayoException e) {
        assert call == null;
        // Do nothing.
    }

    @Override
    public void noNewExchanges() {
        // Do nothing.
    }

    @Override
    public void cancel() {
        canceled = true;
        // Cancel the raw socket so we don't end up doing synchronous I/O.
        if (rawSocket != null) {
            rawSocket.cancel();
        }
    }

    @Override
    public @NonNull Plan retry() {
        return new ConnectPlan(
                taskRunner,
                connectionPool,
                readTimeout,
                writeTimeout,
                connectTimeout,
                pingInterval,
                retryOnConnectionFailure,
                call,
                routePlanner,
                route,
                routes,
                attempt,
                tunnelRequest,
                connectionSpecIndex,
                isTlsFallback
        );
    }

    void closeQuietly() {
        if (socket != null) {
            Jayo.closeQuietly(socket);
        }
    }

    /**
     * @return true if a TLS connection should be retried after {@code e}.
     */
    private static boolean retryTlsHandshake(final @NonNull JayoException e) {
        // e.g. a certificate pinning error.
        if (e instanceof JayoTlsPeerUnverifiedException) {
            return false;
        }

        // If the problem was a CertificateException from the X509TrustManager, do not retry.
        if (e instanceof JayoTlsHandshakeException
                && e.getCause().getCause() instanceof CertificateException) {
            return false;
        }

        // Retry for all other TLS failures.
        if (e instanceof JayoTlsException) {
            return true;
        }

        return false;
    }
}
