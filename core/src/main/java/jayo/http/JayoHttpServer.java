/*
 * Copyright (c) 2026-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package jayo.http;

import jayo.JayoClosedResourceException;
import jayo.http.internal.RealJayoHttpServer;
import jayo.network.NetworkProtocol;
import jayo.tls.Protocol;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;

public sealed interface JayoHttpServer permits RealJayoHttpServer {
    /**
     * @return the local address that this HTTP server's underlying socket is bound to.
     * @throws JayoClosedResourceException If this HTTP server is closed.
     * @throws jayo.JayoException          If an I/O error occurs.
     */
    @NonNull
    InetSocketAddress getLocalAddress();

    /**
     * @return the {@link NetworkProtocol network protocol} to use when opening the underlying NIO server sockets:
     * {@code IPv4} or {@code IPv6}. If null, the default protocol is platform (and possibly configuration) dependent
     * and therefore unspecified.
     * @see <a href="https://docs.oracle.com/javase/8/docs/api/java/net/doc-files/net-properties.html#Ipv4IPv6">
     * java.net.preferIPv4Stack</a> system property
     */
    @Nullable NetworkProtocol getNetworkProtocol();

    /**
     * The protocols supported by ALPN on incoming HTTPS connections in order of preference. This list always contain
     * {@link Protocol#HTTP_1_1}.
     * <p>
     * This list is ignored when {@linkplain #isProtocolNegotiationEnabled() negotiation is disabled}.
     */
    @NonNull
    List<@NonNull Protocol> getProtocols();

    /**
     * True if ALPN is used on incoming HTTPS connections to negotiate a protocol like HTTP/1.1 or HTTP/2. If unset in
     * the builder, it is {@code true} by default. If it was set to {@code false}, ALPN negotiation is disabled and
     * connections are restricted to {@linkplain Protocol#HTTP_1_1 HTTP/1.1}.
     */
    boolean isProtocolNegotiationEnabled();

    /**
     * @return the read timeout for network connections. If unset in the builder, it is 10 seconds.
     */
    @NonNull
    Duration getReadTimeout();

    /**
     * @return the write timeout for network connections. If unset in the builder, it is 10 seconds.
     */
    @NonNull
    Duration getWriteTimeout();

    /**
     * @return the shutdown timeout for this server. If unset in the builder, it is 60 seconds.
     */
    @NonNull
    Duration getShutdownTimeout();

    /**
     * Initiates the graceful shutdown of this server. No new connections will be accepted, but ongoing connections
     * will still continue to process requests during the {@linkplain #getShutdownTimeout() shutdown duration}. If this
     * timeout is reached and some requests were not processed, their connections are then forcibly closed.
     */
    void shutdown();

    /**
     * Initiates the immediate shutdown of this server. No new connections will be accepted, and all ongoing connections
     * are forcibly closed.
     */
    void shutdownNow();

    /**
     * The builder used to create a {@link JayoHttpServer} instance.
     */
    sealed interface Builder permits RealJayoHttpServer.Builder {
        /**
         * Sets the {@link NetworkProtocol network protocol} to use when opening the underlying NIO server sockets:
         * {@code IPv4} or {@code IPv6}. The default protocol is platform (and possibly configuration) dependent and
         * therefore unspecified.
         * <p>
         * This option <b>is only available for Java NIO</b>, so {@linkplain #useNio(boolean) Java NIO mode} is forced
         * when this parameter is set!
         *
         * @see <a href="https://docs.oracle.com/javase/8/docs/api/java/net/doc-files/net-properties.html#Ipv4IPv6">
         * java.net.preferIPv4Stack</a> system property
         */
        @NonNull
        Builder networkProtocol(final @NonNull NetworkProtocol networkProtocol);

        /**
         * Sets the port number to listen to. It must be between 0 and 65_535, inclusive. A port number of 0 means that
         * the port number is automatically allocated, typically from an ephemeral port range. Default is 8080.
         */
        @NonNull
        Builder port(final int port);

        /**
         * Configure the protocols supported by this server to communicate with remote clients. By default, this server
         * will prefer the most efficient transport available, falling back to more ubiquitous protocols. Applications
         * should only call this method to avoid specific compatibility problems, such as web clients that behave
         * incorrectly when HTTP/2 is enabled.
         * <p>
         * This list is ignored when {@linkplain #protocolNegotiationEnabled(boolean) negotiation is disabled}.
         * <p>
         * The following protocols are currently supported:
         * <ul>
         * <li><a href="https://www.w3.org/Protocols/rfc2616/rfc2616.html">http/1.1</a>
         * <li><a href="https://tools.ietf.org/html/rfc7540">h2</a>
         * <li><a href="https://tools.ietf.org/html/rfc7540#section-3.4">h2 with prior knowledge(cleartext only)</a>
         * </ul>
         * <b>This is an evolving set.</b> Future releases may include new protocols. The http/1.1 transport will never
         * be dropped.
         * <p>
         * If multiple protocols are specified,
         * <a href="http://tools.ietf.org/html/draft-ietf-tls-applayerprotoneg">ALPN</a> will be used to negotiate the
         * transport. Protocol negotiation is only attempted for HTTPS URLs.
         * <p>
         * The obsolete {@link Protocol#HTTP_1_0} protocol is not supported in this set.
         *
         * @param protocols the protocols to use, in order of preference. If the list contains
         *                  {@link Protocol#H2_PRIOR_KNOWLEDGE} then that must be the only protocol and HTTPS URLs will
         *                  not be supported. Otherwise, the list must contain {@link Protocol#HTTP_1_1}. The list must
         *                  not contain {@code null} or {@link Protocol#HTTP_1_0}.
         */
        @NonNull
        Builder protocols(final @NonNull List<@NonNull Protocol> protocols);

        /**
         * Sets the maximum number of pending connections waiting to be accepted by this server. Default is 50. If set
         * to 0, then an implementation-specific default is used and therefore unspecified.
         * <p>
         * Its exact semantics are implementation-specific. Some implementations may impose a maximum length or may
         * choose to ignore that parameter altogether.
         */
        @NonNull
        Builder maxPendingConnections(final int maxPendingConnections);

        /**
         * Configure if ALPN is used on incoming HTTPS connections to negotiate a protocol like HTTP/1.1 or HTTP/2.
         * Default is {@code true}. If it was set to {@code false}, ALPN negotiation is disabled and connections are
         * restricted to {@linkplain Protocol#HTTP_1_1 HTTP/1.1}.
         */
        @NonNull
        Builder protocolNegotiationEnabled(final boolean protocolNegotiationEnabled);

        /**
         * Sets the read timeout for network connections. Default is 10 seconds. A timeout of zero is interpreted as
         * an infinite timeout.
         */
        @NonNull
        Builder readTimeout(final @NonNull Duration readTimeout);

        /**
         * Sets the write timeout for network connections. Default is 10 seconds. A timeout of zero is interpreted as
         * an infinite timeout.
         */
        @NonNull
        Builder writeTimeout(final @NonNull Duration writeTimeout);

        /**
         * Sets the shutdown timeout for this server. Default is 60 seconds. A timeout of zero is interpreted as an
         * infinite timeout.
         */
        @NonNull
        Builder shutdownTimeout(final @NonNull Duration shutdownTimeout);

        /**
         * Set to {@code true} to use Java NIO sockets, {@code false} for Java IO ones. Default is true.
         */
        @NonNull
        Builder useNio(final boolean useNio);

        @NonNull
        JayoHttpServer build();
    }
}
