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

package jayo.http;

import jayo.http.internal.connection.RealJayoHttpClient;
import jayo.tls.ClientTlsSocket;
import jayo.tls.Protocol;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.net.ssl.HostnameVerifier;
import java.time.Duration;
import java.util.List;

/**
 * Factory for {@linkplain Call calls}, which can be used to send HTTP requests and read their responses.
 * <h2>JayoHttpClients Should Be Shared</h2>
 * Jayo HTTP performs best when you create a single {@code JayoHttpClient} instance and reuse it for all of your HTTP
 * calls. This is because each client holds its own connection pool and thread pools. Reusing connections and threads
 * reduces latency and saves memory. Conversely, creating a client for each request wastes resources on idle pools.
 * <p>
 * Use {@code JayoHttpClient.create()} to create a shared instance with the default settings:
 * <pre>
 * {@code
 * // The singleton HTTP client.
 * public final JayoHttpClient client = JayoHttpClient.create();
 * }
 * </pre>
 * Or use {@code JayoHttpClient.builder()} to create a shared instance with custom settings:
 * <pre>
 * {@code
 * // The singleton HTTP client.
 * public final JayoHttpClient client = JayoHttpClient.builder()
 *     .addInterceptor(HttpLoggingInterceptor.builder().level(Level.BASIC).build())
 *     .cache(new Cache(cacheDir, cacheSize))
 *     .build();
 * }
 * </pre>
 * <h2>Customize Your Client With newBuilder()</h2>
 * You can customize a shared JayoHttpClient instance with {@link #newBuilder()}. This builds a client that shares the
 * same connection pool, thread pools, and configuration. Use the builder methods to add configuration to the derived
 * client for a specific purpose.
 * <p>
 * This example shows the single instance with default configurations.
 * <pre>
 * {@code
 * public final JayoHttpClient client = JayoHttpClient.builder()
 *     .readTimeout(Duration.ofMillis(1000))
 *     .writeTimeout(Duration.ofMillis(1000))
 *     .build();
 * }
 * </pre>
 * This example shows a call with a short 500-millisecond read timeout and a 1000-millisecond write timeout. The
 * original configuration is kept but can be overridden.
 * <pre>
 * {@code
 * JayoHttpClient eagerClient = client.newBuilder()
 *     .readTimeout(Duration.ofMillis(500))
 *     .build();
 * Response response = eagerClient.newCall(request).execute();
 * }
 * </pre>
 * <h2>Shutdown Isn't Necessary</h2>
 * The threads and connections that are held will be released automatically if they remain idle. But if you are writing
 * an application that needs to aggressively release unused resources, you may do so.
 * <p>
 * Shutdown the dispatcher with {@linkplain Dispatcher#shutdown(Duration)}. This will also cause future calls to the
 * client to be rejected.
 * <pre>
 * {@code
 * // Trigger shutdown of the dispatcher, leaving a few seconds
 * // for asynchronous requests to respond if the network is slow.
 * client.getDispatcher().shutdown(Duration.ofSeconds(5));
 * }
 * </pre>
 * Clear the connection pool with {@linkplain ConnectionPool#evictAll() evictAll()}. Note that the connection pool's
 * daemon thread may not exit immediately.
 * <pre>
 * {@code
 * client.getConnectionPool().evictAll();
 * }
 * </pre>
 * If your client has a cache, call {@linkplain Cache#close() close()}. Note that it is an error to create calls against
 * a cache that is closed, and doing so will cause the call to crash.
 * <pre>
 * {@code
 * client.getCache().close();
 * }
 * </pre>
 * Jayo HTTP uses daemon threads for HTTP/2 connections that are also virtual for Java 21+. These will exit
 * automatically if they remain idle.
 */
public sealed interface JayoHttpClient extends Call.Factory, WebSocket.Factory permits RealJayoHttpClient {
    static @NonNull Builder builder() {
        return new RealJayoHttpClient.Builder();
    }

    /**
     * @return a new {@link JayoHttpClient} with good defaults.
     */
    static @NonNull JayoHttpClient create() {
        return builder().build();
    }

    @NonNull
    Authenticator getAuthenticator();

    @Nullable
    Cache getCache();

    /**
     * @return the default call timeout. If unset in the builder, there is no timeout for complete calls, but there are
     * for connect, write, and read actions within a call.
     * <p>
     * For WebSockets and duplex calls, this call timeout only applies to the initial setup.
     */
    @NonNull
    Duration getCallTimeout();

    @NonNull
    CertificatePinner getCertificatePinner();

    ClientTlsSocket.@NonNull Builder getTlsClientBuilder();

    @NonNull
    ConnectionPool getConnectionPool();

    @NonNull
    List<@NonNull ConnectionSpec> getConnectionSpecs();

    /**
     * @return the connect timeout for network connections. If unset in the builder, it is 10 seconds.
     */
    @NonNull
    Duration getConnectTimeout();

    @NonNull
    CookieJar getCookieJar();

    /**
     * @return the dispatcher used to set policy and execute sync and async requests.
     */
    @NonNull
    Dispatcher getDispatcher();

    @NonNull
    Dns getDns();

    EventListener.@NonNull Factory getEventListenerFactory();

    boolean fastFallback();

    boolean followRedirects();

    boolean followTlsRedirects();

    @NonNull
    HostnameVerifier getHostnameVerifier();

    /**
     * @return an immutable list of interceptors that observe the full span of each call: from before the connection is
     * established (if any) until after the response source is selected (either the origin server, cache, or both).
     */
    @NonNull
    List<@NonNull Interceptor> getInterceptors();

    /**
     * @return the minimum outbound web socket message size (in bytes) that will be compressed. Default is 1024.
     */
    long getMinWebSocketMessageToCompress();

    /**
     * @return an immutable list of interceptors that observe a single network request and response. These interceptors
     * must call {@link Interceptor.Chain#proceed(ClientRequest)} exactly once: it is an error for a network interceptor
     * to short-circuit or repeat a network request.
     */
    @NonNull
    List<@NonNull Interceptor> getNetworkInterceptors();

    /**
     * @return the web socket and HTTP/2 ping interval. If unset in the builder, pings are not sent.
     */
    @NonNull
    Duration getPingInterval();

    @NonNull
    List<@NonNull Protocol> getProtocols();

    /**
     * @return the HTTP proxies that will be used by connections created by this client.
     */
    @NonNull
    Proxies getProxies();

    @NonNull
    Authenticator getProxyAuthenticator();

    /**
     * @return the read timeout for network connections. If unset in the builder, it is 10 seconds.
     */
    @NonNull
    Duration getReadTimeout();

    boolean retryOnConnectionFailure();

    /**
     * @return the web socket close timeout. If unset in the builder, it is 60 seconds.
     */
    @NonNull
    Duration getWebSocketCloseTimeout();

    /**
     * @return the write timeout for network connections. If unset in the builder, it is 10 seconds.
     */
    @NonNull
    Duration getWriteTimeout();

    /**
     * Creates an {@link Address} out of the provided {@link HttpUrl} that uses this client’s DNS, TLS, and proxy
     * configuration.
     */
    @NonNull
    Address address(final @NonNull HttpUrl url);

    @NonNull
    Builder newBuilder();

    /**
     * The builder used to create a {@link JayoHttpClient} instance.
     */
    sealed interface Builder permits RealJayoHttpClient.Builder {
        /**
         * Sets the authenticator used to respond to challenges from origin servers. Use
         * {@link #proxyAuthenticator(Authenticator)} to set the authenticator for proxy servers.
         * <p>
         * If unset, the {@linkplain Authenticator#NONE no authentication} will be attempted.
         */
        @NonNull
        Builder authenticator(final @NonNull Authenticator authenticator);

        /**
         * Sets the response cache to be used to read and write cached responses.
         */
        @NonNull
        Builder cache(final @Nullable Cache cache);

        /**
         * Sets the default timeout for complete calls. Default is zero. A timeout of zero is interpreted as an infinite
         * timeout.
         * <p>
         * This call timeout spans the entire call: resolving DNS, connecting, writing the request body, server
         * processing, and reading the response body. If the call requires redirects or retries, all must be complete
         * within one timeout period.
         * <p>
         * Note: For WebSockets and duplex calls, this call timeout only applies to the initial setup.
         */
        @NonNull
        Builder callTimeout(final @NonNull Duration callTimeout);

        /**
         * Sets the certificate pinner that constrains which certificates are trusted. By default, HTTPS connections
         * rely on only the {@linkplain #tlsConfig(ClientTlsSocket.Builder) TLS client builder} to establish
         * trust. Pinning certificates avoids the need to trust certificate authorities.
         */
        @NonNull
        Builder certificatePinner(final @NonNull CertificatePinner certificatePinner);

        /**
         * Sets the connection pool used to recycle HTTP and HTTPS connections. If unset, a new connection pool will be
         * used.
         */
        @NonNull
        Builder connectionPool(final @NonNull ConnectionPool connectionPool);

        @NonNull
        Builder connectionSpecs(final @NonNull List<@NonNull ConnectionSpec> connectionSpecs);

        /**
         * Sets the connect timeout for network connections. Default is 10 seconds. A timeout of zero is interpreted as
         * an infinite timeout.
         */
        @NonNull
        Builder connectTimeout(final @NonNull Duration connectTimeout);

        /**
         * Sets the handler that can accept cookies from incoming HTTP responses and provides cookies to outgoing HTTP
         * requests.
         * <p>
         * If unset, {@linkplain CookieJar#NO_COOKIES no cookies} will be accepted nor provided.
         */
        @NonNull
        Builder cookieJar(final @NonNull CookieJar cookieJar);

        /**
         * Sets the dispatcher used to set policy and execute sync and async requests.
         */
        @NonNull
        Builder dispatcher(final @NonNull Dispatcher dispatcher);

        /**
         * Sets the DNS service used to lookup IP addresses for hostnames.
         * <p>
         * If unset, the {@linkplain Dns#SYSTEM system-wide default} DNS will be used.
         */
        @NonNull
        Builder dns(final @NonNull Dns dns);

        /**
         * Configure a single client-scoped listener that will receive all analytic events for this client.
         * <p>
         * See {@link EventListener} for semantics and restrictions on listener implementations.
         */
        @NonNull
        Builder eventListener(final @NonNull EventListener eventListener);

        /**
         * Configure a factory to provide per-call scoped listeners that will receive analytic events for this client.
         * <p>
         * See {@link EventListener} for semantics and restrictions on listener implementations.
         */
        @NonNull
        Builder eventListenerFactory(final EventListener.@NonNull Factory eventListenerFactory);

        /**
         * Configure this client to perform fast fallbacks by attempting multiple connections concurrently, returning
         * once any connection connects successfully. Defaults to {@code true}.
         * <p>
         * This implements Happy Eyeballs (<a href="https://datatracker.ietf.org/doc/html/rfc6555">RFC 6555</a>),
         * balancing connect latency vs. wasted resources.
         */
        @NonNull
        Builder fastFallback(final boolean fastFallback);

        /**
         * Configure this client to follow redirects. Defaults to {@code true}.
         */
        @NonNull
        Builder followRedirects(final boolean followRedirects);

        /**
         * Configure this client to allow protocol redirects from HTTPS to HTTP and from HTTP to HTTPS. Redirects are
         * still first restricted by {@link #followRedirects(boolean)}. Defaults to {@code true}.
         */
        @NonNull
        Builder followTlsRedirects(final boolean followProtocolRedirects);

        /**
         * Sets the verifier used to confirm that response certificates apply to requested hostnames for HTTPS
         * connections. If unset, a default hostname verifier will be used.
         */
        @NonNull
        Builder hostnameVerifier(final @NonNull HostnameVerifier hostnameVerifier);

        /**
         * @return a modifiable list of interceptors that observe the full span of each call: from before the connection
         * is established (if any) until after the response source is selected (either the origin server, cache, or
         * both).
         */
        @NonNull
        List<@NonNull Interceptor> interceptors();

        @NonNull
        Builder addInterceptor(final @NonNull Interceptor interceptor);

        /**
         * @return a modifiable list of interceptors that observe a single network request and response. These
         * interceptors must call {@link Interceptor.Chain#proceed(ClientRequest)} exactly once: it is an error for a
         * network interceptor to short-circuit or repeat a network request.
         */
        @NonNull
        List<@NonNull Interceptor> networkInterceptors();

        @NonNull
        Builder addNetworkInterceptor(final @NonNull Interceptor interceptor);

        /**
         * Sets the interval between HTTP/2 and web socket pings initiated by this client. Use this to automatically
         * send ping frames until either the connection fails or it is closed. This keeps the connection alive and may
         * detect connectivity failures.
         * <p>
         * Default is zero. A value of zero disables client-initiated pings.
         * <p>
         * If the server does not respond to each ping with a pong within {@code interval}, this client will assume that
         * connectivity has been lost.
         * <ul>
         * <li>When this happens on a web socket, the connection is canceled and its listener is
         * {@link WebSocketListener#onFailure(WebSocket, Throwable, ClientResponse) notified}.
         * <li>When it happens on an HTTP/2 connection, the connection is closed, and any calls it is carrying will fail
         * with a {@link jayo.JayoException}.
         * </ul>
         */
        @NonNull
        Builder pingInterval(final @NonNull Duration interval);

        /**
         * Configure the protocols used by this client to communicate with remote servers. By default, this client will
         * prefer the most efficient transport available, falling back to more ubiquitous protocols. Applications should
         * only call this method to avoid specific compatibility problems, such as web servers that behave incorrectly
         * when HTTP/2 is enabled.
         * <p>
         * The following protocols are currently supported:
         * <ul>
         * <li><a href="https://www.w3.org/Protocols/rfc2616/rfc2616.html">http/1.1</a>
         * <li><a href="https://tools.ietf.org/html/rfc7540>h2</a>
         * <li><a href="https://tools.ietf.org/html/rfc7540#section-3.4>h2 with prior knowledge(cleartext only)</a>
         * </ul>
         * <b>This is an evolving set.</b> Future releases include support for transitional protocols. The http/1.1
         * transport will never be dropped.
         * <p>
         * If multiple protocols are specified,
         * <a href="http://tools.ietf.org/html/draft-ietf-tls-applayerprotoneg">ALPN</a> will be used to negotiate the
         * transport. Protocol negotiation is only attempted for HTTPS URLs.
         * <p>
         * {@link Protocol#HTTP_1_0} is not supported in this set. Requests are initiated with {@code HTTP/1.1}. If the
         * server responds with {@code HTTP/1.0}, that will be exposed by {@link ClientResponse#getProtocol()}.
         *
         * @param protocols the protocols to use, in order of preference. If the list contains
         *                  {@link Protocol#H2_PRIOR_KNOWLEDGE} then that must be the only protocol and HTTPS URLs will
         *                  not be supported. Otherwise, the list must contain {@link Protocol#HTTP_1_1}. The list must
         *                  not contain {@code null} or {@link Protocol#HTTP_1_0}.
         */
        @NonNull
        Builder protocols(final @NonNull List<@NonNull Protocol> protocols);

        /**
         * Sets the HTTP proxies that will be used by connections created by this client. If unset,
         * {@link Proxies#EMPTY} will be used.
         */
        @NonNull
        Builder proxies(final @NonNull Proxies proxies);

        /**
         * Sets the authenticator used to respond to challenges from proxy servers. Use
         * {@link #authenticator(Authenticator)} to set the authenticator for origin servers.
         * <p>
         * If unset, the {@linkplain Authenticator#NONE no authentication} will be attempted.
         */
        @NonNull
        Builder proxyAuthenticator(final @NonNull Authenticator proxyAuthenticator);

        /**
         * Sets the read timeout for network connections. Default is 10 seconds. A timeout of zero is interpreted as
         * an infinite timeout.
         */
        @NonNull
        Builder readTimeout(final @NonNull Duration readTimeout);

        /**
         * Configure this client to retry or not when a connectivity problem is encountered. Default is {@code true}, so
         * this client silently recovers from the following problems:
         * <ul>
         * <li><b>Unreachable IP addresses.</b> If the URL's host has multiple IP addresses, failure to reach any
         * individual IP address doesn't fail the overall request. This can increase the availability of multi-homed
         * services.
         * <li><b>Stale pooled connections.</b> The {@link ConnectionPool} reuses sockets to decrease request latency,
         * but these connections will occasionally time out.
         * </ul>
         * Set this to {@code false} to avoid retrying requests when doing so is destructive. In this case, the calling
         * application should do its own recovery of connectivity failures.
         */
        @NonNull
        Builder retryOnConnectionFailure(final boolean retryOnConnectionFailure);

        /**
         * Sets the TLS client builder used to secure HTTPS connections. If unset, the system defaults will be used.
         * <p>
         * Most applications should not call this method and instead use the system defaults. Those classes include
         * special optimizations that can be lost if the implementations are decorated.
         * <p>
         * If necessary, you can create and configure the defaults yourself with the following code:
         * <pre>
         * {@code
         * TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
         *   TrustManagerFactory.getDefaultAlgorithm());
         * trustManagerFactory.init((KeyStore) null);
         * ClientHandshakeCertificates clientHandshakeCerts = ClientHandshakeCertificates.create(
         *   trustManagerFactory);
         *
         * ClientTlsSocket.Builder tlsClientBuilder = ClientTlsSocket.builder(clientHandshakeCerts);
         *
         * JayoHttpClient client = JayoHttpClient.builder()
         *     .tlsClientBuilder(tlsClientBuilder)
         *     .build();
         * }
         * </pre>
         * Note: Jayo HTTP will always check that the server's certificates match its hostname using the
         * {@link HostnameVerifier}.
         */
        @NonNull
        Builder tlsConfig(final ClientTlsSocket.@NonNull Builder clientTlsConfig);

        /**
         * Set to {@code true} to use Java NIO sockets, {@code false} for Java IO ones. Default is true.
         */
        @NonNull
        Builder useNio(final boolean useNio);

        /**
         * Sets the minimum outbound web socket message size (in bytes) that will be compressed. Default is 1024. Set to
         * zero to enable compression for all outbound messages.
         */
        @NonNull
        Builder minWebSocketMessageToCompress(final long bytes);

        /**
         * Sets the close timeout for web socket connections. Default is 60 seconds. A timeout of zero is interpreted as
         * an infinite timeout.
         * <p>
         * This close timeout is the maximum amount of time after the client calls
         * {@link WebSocket#close(short, String)} to wait for a graceful shutdown. If the server doesn't respond, the
         * web socket will be canceled.
         */
        @NonNull
        Builder webSocketCloseTimeout(final @NonNull Duration webSocketCloseTimeout);

        /**
         * Sets the write timeout for network connections. Default is 10 seconds. A timeout of zero is interpreted as
         * an infinite timeout.
         */
        @NonNull
        Builder writeTimeout(final @NonNull Duration writeTimeout);

        @NonNull
        JayoHttpClient build();
    }
}
