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

import jayo.http.*;
import jayo.http.internal.CertificateChainCleaner;
import jayo.http.internal.JayoHostnameVerifier;
import jayo.http.internal.RealCertificatePinner;
import jayo.http.internal.ws.RealWebSocket;
import jayo.network.NetworkSocket;
import jayo.scheduler.TaskRunner;
import jayo.tls.ClientHandshakeCertificates;
import jayo.tls.ClientTlsSocket;
import jayo.tls.Protocol;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.net.ssl.HostnameVerifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.function.Consumer;

public final class RealJayoHttpClient implements JayoHttpClient {
    static final System.Logger LOGGER = System.getLogger("jayo.http.JayoHttpClient");

    private final @NonNull Authenticator authenticator;
    private final @Nullable Cache cache;
    private final @NonNull Duration callTimeout;
    private final @Nullable CertificateChainCleaner certificateChainCleaner;
    private final @NonNull CertificatePinner certificatePinner;
    private final ClientTlsSocket.@Nullable Builder clientTlsSocketBuilderOrNull;
    private final @NonNull ConnectionPool connectionPool;
    private final @NonNull List<@NonNull ConnectionSpec> connectionSpecs;
    final NetworkSocket.@NonNull Builder networkSocketBuilder;
    private final @NonNull CookieJar cookieJar;
    final @NonNull RealDispatcher dispatcher;
    private final @NonNull Dns dns;
    private final EventListener.@NonNull Factory eventListenerFactory;
    private final boolean fastFallback;
    private final boolean followRedirects;
    private final boolean followTlsRedirects;
    private final @NonNull HostnameVerifier hostnameVerifier;
    private final @NonNull List<@NonNull Interceptor> interceptors;
    private final long minWebSocketMessageToCompress;
    private final @NonNull List<@NonNull Interceptor> networkInterceptors;
    private final @NonNull Duration pingInterval;
    private final @NonNull List<@NonNull Protocol> protocols;
    private final @NonNull Proxies proxies;
    private final @NonNull Authenticator proxyAuthenticator;
    private final boolean retryOnConnectionFailure;
    final @NonNull RouteDatabase routeDatabase;
    final @NonNull TaskRunner taskRunner;
    private final @NonNull Duration webSocketCloseTimeout;

    private RealJayoHttpClient(final @NonNull Builder builder) {
        assert builder != null;

        this.authenticator = builder.authenticator;
        this.cache = builder.cache;
        this.callTimeout = builder.callTimeout;
        this.connectionSpecs = List.copyOf(builder.connectionSpecs);
        this.cookieJar = builder.cookieJar;
        this.dispatcher = builder.dispatcher;
        this.dns = builder.dns;
        this.eventListenerFactory = builder.eventListenerFactory;
        this.fastFallback = builder.fastFallback;
        this.followRedirects = builder.followRedirects;
        this.followTlsRedirects = builder.followTlsRedirects;
        this.hostnameVerifier = builder.hostnameVerifier;
        this.interceptors = List.copyOf(builder.interceptors);
        this.minWebSocketMessageToCompress = builder.minWebSocketMessageToCompress;
        this.networkInterceptors = List.copyOf(builder.networkInterceptors);
        this.networkSocketBuilder = builder.networkSocketBuilder.clone();
        this.pingInterval = builder.pingInterval;
        this.protocols = List.copyOf(builder.protocols);
        this.proxies = (builder.proxies != null) ? builder.proxies : Proxies.builder().build();
        this.proxyAuthenticator = builder.proxyAuthenticator;
        this.retryOnConnectionFailure = builder.retryOnConnectionFailure;
        this.routeDatabase = (builder.routeDatabase != null) ? builder.routeDatabase : new RouteDatabase();
        this.taskRunner = builder.taskRunner;
        this.webSocketCloseTimeout = builder.webSocketCloseTimeout;

        if (builder.connectionPool != null) {
            this.connectionPool = builder.connectionPool;
        } else {
            this.connectionPool = new RealConnectionPool(taskRunner, 5, Duration.ofMinutes(5));
            // Cache the pool in the builder so that it will be shared with other clients
            builder.connectionPool = connectionPool;
        }

        // TLS
        if (connectionSpecs.stream().noneMatch(ConnectionSpec::isTls)) {
            this.clientTlsSocketBuilderOrNull = null;
            this.certificateChainCleaner = null;
            this.certificatePinner = CertificatePinner.DEFAULT;

        } else if (builder.clientTlsSocketBuilderOrNull != null) {
            this.clientTlsSocketBuilderOrNull = builder.clientTlsSocketBuilderOrNull;
            assert builder.certificateChainCleaner != null;
            this.certificateChainCleaner = builder.certificateChainCleaner;
            this.certificatePinner = ((RealCertificatePinner) builder.certificatePinner)
                    .withCertificateChainCleaner(certificateChainCleaner);

        } else {
            this.clientTlsSocketBuilderOrNull = ClientTlsSocket.builder(ClientHandshakeCertificates.create());
            final var x509TrustManager = clientTlsSocketBuilderOrNull.getHandshakeCertificates().getTrustManager();
            this.certificateChainCleaner = new CertificateChainCleaner(x509TrustManager);
            this.certificatePinner = ((RealCertificatePinner) builder.certificatePinner)
                    .withCertificateChainCleaner(certificateChainCleaner);
        }
    }

    @Override
    public @NonNull Duration getCallTimeout() {
        return callTimeout;
    }

    @Override
    public @NonNull CertificatePinner getCertificatePinner() {
        return certificatePinner;
    }

    @Override
    public ClientTlsSocket.@NonNull Builder getTlsClientBuilder() {
        if (clientTlsSocketBuilderOrNull == null) {
            throw new IllegalStateException("CLEARTEXT-only client");
        }
        return clientTlsSocketBuilderOrNull;
    }

    @Override
    public @NonNull ConnectionPool getConnectionPool() {
        return connectionPool;
    }

    @Override
    public @NonNull List<@NonNull ConnectionSpec> getConnectionSpecs() {
        return connectionSpecs;
    }

    @Override
    public @NonNull Duration getConnectTimeout() {
        return networkSocketBuilder.getConnectTimeout();
    }

    @Override
    public @NonNull CookieJar getCookieJar() {
        return cookieJar;
    }

    @Override
    public @NonNull Dispatcher getDispatcher() {
        return dispatcher;
    }

    @Override
    public @NonNull Dns getDns() {
        return dns;
    }

    @Override
    public EventListener.@NonNull Factory getEventListenerFactory() {
        return eventListenerFactory;
    }

    @Override
    public boolean fastFallback() {
        return fastFallback;
    }

    @Override
    public boolean followRedirects() {
        return followRedirects;
    }

    @Override
    public boolean followTlsRedirects() {
        return followTlsRedirects;
    }

    @Override
    public @NonNull HostnameVerifier getHostnameVerifier() {
        return hostnameVerifier;
    }

    @Override
    public @NonNull Authenticator getAuthenticator() {
        return authenticator;
    }

    @Override
    public @Nullable Cache getCache() {
        return cache;
    }

    @Override
    public @NonNull Authenticator getProxyAuthenticator() {
        return proxyAuthenticator;
    }

    @Override
    public @NonNull List<@NonNull Interceptor> getInterceptors() {
        return interceptors;
    }

    @Override
    public long getMinWebSocketMessageToCompress() {
        return minWebSocketMessageToCompress;
    }

    @Override
    public @NonNull List<@NonNull Interceptor> getNetworkInterceptors() {
        return networkInterceptors;
    }

    @Override
    public @NonNull Duration getPingInterval() {
        return pingInterval;
    }

    @Override
    public @NonNull List<@NonNull Protocol> getProtocols() {
        return protocols;
    }

    @Override
    public @NonNull Proxies getProxies() {
        return proxies;
    }

    @Override
    public @NonNull Duration getReadTimeout() {
        return networkSocketBuilder.getReadTimeout();
    }

    @Override
    public boolean retryOnConnectionFailure() {
        return retryOnConnectionFailure;
    }

    @Override
    public @NonNull Duration getWebSocketCloseTimeout() {
        return webSocketCloseTimeout;
    }

    @Override
    public @NonNull Duration getWriteTimeout() {
        return networkSocketBuilder.getWriteTimeout();
    }

    @Override
    public @NonNull Address address(final @NonNull HttpUrl url) {
        Objects.requireNonNull(url);

        ClientTlsSocket.@Nullable Builder useClientTlsSocketBuilder = null;
        HostnameVerifier useHostnameVerifier = null;
        CertificatePinner useCertificatePinner = null;
        if (url.isHttps()) {
            useClientTlsSocketBuilder = getTlsClientBuilder();
            useHostnameVerifier = hostnameVerifier;
            useCertificatePinner = certificatePinner;
        }

        return new RealAddress(
                url.getHost(),
                url.getPort(),
                dns,
                useClientTlsSocketBuilder,
                useHostnameVerifier,
                useCertificatePinner,
                protocols,
                connectionSpecs,
                proxies.select(url),
                proxyAuthenticator
        );
    }

    @Override
    public @NonNull Call newCall(final @NonNull ClientRequest request, final @NonNull Tag<?> @NonNull ... tags) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(tags);

        return new RealCall(this, request, tags, false);
    }

    @Override
    public @NonNull WebSocket newWebSocket(final @NonNull ClientRequest request,
                                           final @NonNull WebSocketListener listener,
                                           final @NonNull Tag<?> @NonNull ... tags) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(listener);
        Objects.requireNonNull(tags);

        final var webSocket = new RealWebSocket(
                taskRunner,
                request,
                listener,
                new Random(),
                pingInterval,
                // `extensions` is always null for clients:
                null,
                minWebSocketMessageToCompress,
                webSocketCloseTimeout
        );
        webSocket.connect(this, tags);
        return webSocket;
    }

    @Override
    public @NonNull Builder newBuilder() {
        return new Builder(this);
    }

    public static final class Builder implements JayoHttpClient.Builder {
        private static final @NonNull List<@NonNull ConnectionSpec> DEFAULT_CONNECTION_SPECS =
                List.of(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT);
        private static final @NonNull List<@NonNull Protocol> DEFAULT_PROTOCOLS =
                List.of(Protocol.HTTP_1_1, Protocol.HTTP_2);
        private static final @NonNull Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
        private static final @NonNull Duration NO_TIMEOUT = Duration.ZERO;

        private @NonNull Authenticator authenticator;
        private @Nullable Cache cache = null;
        private @NonNull Duration callTimeout;
        private @Nullable CertificateChainCleaner certificateChainCleaner = null;
        private @NonNull CertificatePinner certificatePinner;
        private ClientTlsSocket.@Nullable Builder clientTlsSocketBuilderOrNull = null;
        private @Nullable ConnectionPool connectionPool = null;
        private @NonNull List<@NonNull ConnectionSpec> connectionSpecs;
        private @NonNull CookieJar cookieJar;
        private @NonNull RealDispatcher dispatcher;
        private @NonNull Dns dns;
        private EventListener.@NonNull Factory eventListenerFactory;
        private boolean fastFallback = true;
        private boolean followRedirects = true;
        private boolean followTlsRedirects = true;
        private @NonNull HostnameVerifier hostnameVerifier;
        private final @NonNull List<@NonNull Interceptor> interceptors = new ArrayList<>();
        private long minWebSocketMessageToCompress = RealWebSocket.DEFAULT_MINIMUM_DEFLATE_SIZE;
        private final @NonNull List<@NonNull Interceptor> networkInterceptors = new ArrayList<>();
        private final NetworkSocket.@NonNull Builder networkSocketBuilder;
        private @NonNull Duration pingInterval;
        private @NonNull List<@NonNull Protocol> protocols;
        private @NonNull Proxies proxies;
        private @NonNull Authenticator proxyAuthenticator;
        private boolean retryOnConnectionFailure = true;
        private @Nullable RouteDatabase routeDatabase = null;
        private @NonNull TaskRunner taskRunner;
        private @NonNull Duration webSocketCloseTimeout;

        public Builder() {
            this.authenticator = Authenticator.NONE;
            this.callTimeout = NO_TIMEOUT;
            this.certificatePinner = CertificatePinner.DEFAULT;
            this.connectionSpecs = DEFAULT_CONNECTION_SPECS;
            this.cookieJar = CookieJar.NO_COOKIES;
            this.dispatcher = new RealDispatcher.Builder().build();
            this.dns = Dns.SYSTEM;
            this.eventListenerFactory = ignoredCall -> EventListener.NONE;
            this.hostnameVerifier = JayoHostnameVerifier.INSTANCE;
            this.networkSocketBuilder = NetworkSocket.builder()
                    .connectTimeout(DEFAULT_TIMEOUT)
                    .readTimeout(DEFAULT_TIMEOUT)
                    .writeTimeout(DEFAULT_TIMEOUT);
            this.pingInterval = NO_TIMEOUT;
            this.protocols = DEFAULT_PROTOCOLS;
            proxies = Proxies.EMPTY;
            this.proxyAuthenticator = Authenticator.DEFAULT_PROXY_AUTHENTICATOR;
            this.taskRunner = DEFAULT_TASK_RUNNER;
            this.webSocketCloseTimeout = RealWebSocket.CANCEL_AFTER_CLOSE_TIMEOUT;
        }

        private Builder(final @NonNull RealJayoHttpClient jayoHttpClient) {
            assert jayoHttpClient != null;

            this.authenticator = jayoHttpClient.authenticator;
            this.cache = jayoHttpClient.cache;
            this.callTimeout = jayoHttpClient.callTimeout;
            this.certificateChainCleaner = jayoHttpClient.certificateChainCleaner;
            this.certificatePinner = jayoHttpClient.certificatePinner;
            this.clientTlsSocketBuilderOrNull = jayoHttpClient.clientTlsSocketBuilderOrNull;
            this.connectionPool = jayoHttpClient.connectionPool;
            this.connectionSpecs = jayoHttpClient.connectionSpecs;
            this.cookieJar = jayoHttpClient.cookieJar;
            this.dispatcher = jayoHttpClient.dispatcher;
            this.dns = jayoHttpClient.dns;
            this.eventListenerFactory = jayoHttpClient.eventListenerFactory;
            this.fastFallback = jayoHttpClient.fastFallback;
            this.followRedirects = jayoHttpClient.followRedirects;
            this.followTlsRedirects = jayoHttpClient.followTlsRedirects;
            this.hostnameVerifier = jayoHttpClient.hostnameVerifier;
            this.interceptors.addAll(jayoHttpClient.interceptors);
            this.minWebSocketMessageToCompress = jayoHttpClient.minWebSocketMessageToCompress;
            this.networkInterceptors.addAll(jayoHttpClient.networkInterceptors);
            this.networkSocketBuilder = jayoHttpClient.networkSocketBuilder.clone();
            this.pingInterval = jayoHttpClient.pingInterval;
            this.protocols = jayoHttpClient.protocols;
            this.proxies = jayoHttpClient.proxies;
            this.proxyAuthenticator = jayoHttpClient.proxyAuthenticator;
            this.retryOnConnectionFailure = jayoHttpClient.retryOnConnectionFailure;
            this.routeDatabase = jayoHttpClient.routeDatabase;
            this.taskRunner = jayoHttpClient.taskRunner;
            this.webSocketCloseTimeout = jayoHttpClient.webSocketCloseTimeout;
        }

        @Override
        public @NonNull Builder authenticator(final @NonNull Authenticator authenticator) {
            this.authenticator = Objects.requireNonNull(authenticator);
            return this;
        }

        @Override
        public @NonNull Builder cache(final @Nullable Cache cache) {
            this.cache = cache;
            return this;
        }

        @Override
        public @NonNull Builder callTimeout(final @NonNull Duration callTimeout) {
            this.callTimeout = checkDuration("callTimeout", callTimeout);
            return this;
        }

        @Override
        public @NonNull Builder certificatePinner(final @NonNull CertificatePinner certificatePinner) {
            Objects.requireNonNull(certificatePinner);

            if (!certificatePinner.equals(this.certificatePinner)) {
                this.routeDatabase = null;
            }

            this.certificatePinner = certificatePinner;
            return this;
        }

        @Override
        public @NonNull Builder connectionPool(final @NonNull ConnectionPool connectionPool) {
            this.connectionPool = Objects.requireNonNull(connectionPool);
            return this;
        }

        @Override
        public @NonNull Builder connectionSpecs(final @NonNull List<@NonNull ConnectionSpec> connectionSpecs) {
            Objects.requireNonNull(connectionSpecs);

            if (!connectionSpecs.equals(this.connectionSpecs)) {
                this.routeDatabase = null;
            }

            this.connectionSpecs = List.copyOf(connectionSpecs);
            return this;
        }

        @Override
        public @NonNull Builder cookieJar(final @NonNull CookieJar cookieJar) {
            this.cookieJar = Objects.requireNonNull(cookieJar);
            return this;
        }

        @Override
        public @NonNull Builder dispatcher(final @NonNull Dispatcher dispatcher) {
            Objects.requireNonNull(dispatcher);
            if (!(dispatcher instanceof RealDispatcher realDispatcher)) {
                throw new IllegalArgumentException("dispatcher must be a RealDispatcher");
            }
            this.dispatcher = realDispatcher;
            return this;
        }

        @Override
        public @NonNull Builder dns(final @NonNull Dns dns) {
            Objects.requireNonNull(dns);

            if (!dns.equals(this.dns)) {
                this.routeDatabase = null;
            }
            this.dns = dns;
            return this;
        }

        @Override
        public @NonNull Builder eventListener(@NonNull EventListener eventListener) {
            Objects.requireNonNull(eventListener);
            this.eventListenerFactory = ignoredCall -> eventListener;
            return this;
        }

        @Override
        public @NonNull Builder eventListenerFactory(
                final EventListener.@NonNull Factory eventListenerFactory) {
            this.eventListenerFactory = Objects.requireNonNull(eventListenerFactory);
            return this;
        }

        @Override
        public @NonNull Builder fastFallback(final boolean fastFallback) {
            this.fastFallback = fastFallback;
            return this;
        }

        @Override
        public @NonNull Builder followRedirects(final boolean followRedirects) {
            this.followRedirects = followRedirects;
            return this;
        }

        @Override
        public @NonNull Builder followTlsRedirects(final boolean followProtocolRedirects) {
            this.followTlsRedirects = followProtocolRedirects;
            return this;
        }

        @Override
        public @NonNull Builder hostnameVerifier(final @NonNull HostnameVerifier hostnameVerifier) {
            Objects.requireNonNull(hostnameVerifier);

            if (!hostnameVerifier.equals(this.hostnameVerifier)) {
                this.routeDatabase = null;
            }

            this.hostnameVerifier = hostnameVerifier;
            return this;
        }

        @Override
        public @NonNull List<@NonNull Interceptor> interceptors() {
            return interceptors;
        }

        @Override
        public @NonNull Builder addInterceptor(final @NonNull Interceptor interceptor) {
            Objects.requireNonNull(interceptor);
            interceptors.add(interceptor);
            return this;
        }

        @Override
        public @NonNull Builder minWebSocketMessageToCompress(final long bytes) {
            if (bytes < 0) {
                throw new IllegalArgumentException("minWebSocketMessageToCompress < 0L: " + bytes);
            }
            this.minWebSocketMessageToCompress = bytes;
            return this;
        }

        @Override
        public @NonNull List<@NonNull Interceptor> networkInterceptors() {
            return networkInterceptors;
        }

        @Override
        public @NonNull Builder addNetworkInterceptor(@NonNull Interceptor interceptor) {
            Objects.requireNonNull(interceptor);
            networkInterceptors.add(interceptor);
            return this;
        }

        @Override
        public @NonNull Builder networkConfig(
                final @NonNull Consumer<NetworkSocket.@NonNull Builder> networkConfigurer) {
            Objects.requireNonNull(networkConfigurer);
            networkConfigurer.accept(networkSocketBuilder);
            return this;
        }

        @Override
        public @NonNull Builder protocols(final @NonNull List<@NonNull Protocol> protocols) {
            Objects.requireNonNull(protocols);

            // Create a private copy of the list.
            final var protocolsCopy = new ArrayList<>(protocols);

            // Validate that the list has everything we require and nothing we forbid.
            if (!protocolsCopy.contains(Protocol.H2_PRIOR_KNOWLEDGE) && !protocolsCopy.contains(Protocol.HTTP_1_1)) {
                throw new IllegalArgumentException(
                        "protocols must contain h2_prior_knowledge or http/1.1: " + protocolsCopy);
            }
            if (protocolsCopy.contains(Protocol.H2_PRIOR_KNOWLEDGE) && protocolsCopy.size() > 1) {
                throw new IllegalArgumentException(
                        "protocols containing h2_prior_knowledge cannot use other protocols: " + protocolsCopy);
            }
            if (protocolsCopy.contains(Protocol.HTTP_1_0)) {
                throw new IllegalArgumentException("protocols must not contain http/1.0: " + protocolsCopy);
            }
            if (protocolsCopy.contains(null)) {
                throw new IllegalArgumentException("protocols must not contain null");
            }

            if (!protocolsCopy.equals(this.protocols)) {
                this.routeDatabase = null;
            }

            // Assign as an unmodifiable list. This is effectively immutable.
            this.protocols = List.copyOf(protocolsCopy);
            return this;
        }

        @Override
        public @NonNull Builder proxies(final @NonNull Proxies proxies) {
            Objects.requireNonNull(proxies);

            if (!proxies.equals(this.proxies)) {
                this.routeDatabase = null;
            }

            this.proxies = proxies;
            return this;
        }

        @Override
        public @NonNull Builder proxyAuthenticator(final @NonNull Authenticator proxyAuthenticator) {
            Objects.requireNonNull(proxyAuthenticator);

            if (!proxyAuthenticator.equals(this.proxyAuthenticator)) {
                this.routeDatabase = null;
            }

            this.proxyAuthenticator = proxyAuthenticator;
            return this;
        }

        @Override
        public @NonNull Builder retryOnConnectionFailure(final boolean retryOnConnectionFailure) {
            this.retryOnConnectionFailure = retryOnConnectionFailure;
            return this;
        }

        @Override
        public @NonNull Builder tlsConfig(final ClientTlsSocket.@NonNull Builder clientTlsConfig) {
            Objects.requireNonNull(clientTlsConfig);

            if (!clientTlsConfig.equals(this.clientTlsSocketBuilderOrNull)) {
                this.routeDatabase = null;
            }

            this.clientTlsSocketBuilderOrNull = clientTlsConfig;
            final var x509TrustManager = clientTlsConfig.getHandshakeCertificates().getTrustManager();
            this.certificateChainCleaner = new CertificateChainCleaner(x509TrustManager);
            return this;
        }

        @Override
        public @NonNull Builder pingInterval(@NonNull Duration interval) {
            this.pingInterval = checkDuration("pingInterval", interval);
            return this;
        }

        @Override
        public @NonNull Builder webSocketCloseTimeout(final @NonNull Duration webSocketCloseTimeout) {
            this.webSocketCloseTimeout = checkDuration("webSocketCloseTimeout", webSocketCloseTimeout);
            return this;
        }

        @Override
        public @NonNull JayoHttpClient build() {
            return new RealJayoHttpClient(this);
        }

        @NonNull
        Builder taskRunner(final @NonNull TaskRunner taskRunner) {
            assert taskRunner != null;

            this.taskRunner = taskRunner;
            return this;
        }

        private static final @NonNull Duration MIN_DURATION = Duration.ofMillis(1L);
        private static final @NonNull Duration MAX_DURATION = Duration.ofHours(1L);

        private static @NonNull Duration checkDuration(final @NonNull String name, final @NonNull Duration timeout) {
            assert name != null;

            Objects.requireNonNull(timeout, name + " == null");
            if (timeout.isNegative()) {
                throw new IllegalArgumentException(name + " < 0");
            }
            if (!timeout.isZero() && timeout.compareTo(MIN_DURATION) < 0) {
                throw new IllegalArgumentException(name + " < 1ms");
            }
            if (timeout.compareTo(MAX_DURATION) > 0) {
                throw new IllegalArgumentException(name + " > 1h");
            }
            return timeout;
        }
    }
}
