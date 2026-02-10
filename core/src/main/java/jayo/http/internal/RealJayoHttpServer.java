/*
 * Copyright (c) 2026-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package jayo.http.internal;

import jayo.JayoClosedResourceException;
import jayo.http.JayoHttpServer;
import jayo.http.tools.JayoHttpUtils;
import jayo.network.NetworkProtocol;
import jayo.network.NetworkServer;
import jayo.tls.Protocol;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.System.Logger.Level.INFO;

public final class RealJayoHttpServer implements JayoHttpServer {
    static final System.Logger LOGGER = System.getLogger("jayo.http.JayoHttpServer");
    private static final @NonNull AtomicInteger INSTANCE_NUMBER = new AtomicInteger(0);

    private final int instanceNumber;
    private final @Nullable NetworkProtocol networkProtocol;
    private final @NonNull NetworkServer networkServer;
    private final NetworkServer.@NonNull Builder networkServerBuilder;
    private final boolean protocolNegotiationEnabled;
    private final @NonNull List<@NonNull Protocol> protocols;
    private final @NonNull Duration shutdownTimeout;

    private final @NonNull Thread accepterThread;

    private RealJayoHttpServer(final @NonNull Builder builder) {
        assert builder != null;

        this.networkProtocol = builder.networkProtocol;
        this.networkServerBuilder = builder.networkServerBuilder.clone();
        this.protocolNegotiationEnabled = builder.protocolNegotiationEnabled;
        this.protocols = List.copyOf(builder.protocols);
        this.shutdownTimeout = builder.shutdownTimeout;

        if (networkProtocol != null) {
            networkServerBuilder.networkProtocol(networkProtocol);
        }
        final var localAddress = HostnameUtils.localHostAddress(networkProtocol);
        this.networkServer = networkServerBuilder.bindTcp(new InetSocketAddress(localAddress, builder.port));

        // Register a hook to trigger shutdown() on JVM exit
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

        this.instanceNumber = INSTANCE_NUMBER.getAndIncrement();
        // start the accept incoming connections loop in a platform thread.
        this.accepterThread = new Thread(
                null,
                this::acceptConnections,
                "JayoHttpServer_accepter#" + instanceNumber,
                0,
                false);
        accepterThread.start();
    }

    private void acceptConnections() {
        if (LOGGER.isLoggable(INFO)) {
            LOGGER.log(INFO, "JayoHttpServer#{0} started", instanceNumber);
        }
        try {
            // accept incoming connections loop
            while (!Thread.interrupted()) {
                try {
                    final var accepted = networkServer.accept();
                } catch (JayoClosedResourceException jcre) {
                    break;
                }
            }
        } finally {
            // ensure the server is closed
            JayoHttpUtils.closeQuietly(networkServer);
        }
    }

    @Override
    public @NonNull InetSocketAddress getLocalAddress() {
        return networkServer.getLocalAddress();
    }

    @Override
    public @Nullable NetworkProtocol getNetworkProtocol() {
        return networkProtocol;
    }

    @Override
    public @NonNull List<@NonNull Protocol> getProtocols() {
        return protocols;
    }

    @Override
    public boolean isProtocolNegotiationEnabled() {
        return protocolNegotiationEnabled;
    }

    @Override
    public @NonNull Duration getReadTimeout() {
        return networkServerBuilder.getReadTimeout();
    }

    @Override
    public @NonNull Duration getShutdownTimeout() {
        return shutdownTimeout;
    }

    @Override
    public @NonNull Duration getWriteTimeout() {
        return networkServerBuilder.getWriteTimeout();
    }

    @Override
    public void shutdown() {
        try {
            accepterThread.interrupt();
            accepterThread.join(shutdownTimeout.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Retain interrupted status.
        } finally {
            JayoHttpUtils.closeQuietly(networkServer);
        }
    }

    @Override
    public void shutdownNow() {

    }

    public static final class Builder implements JayoHttpServer.Builder {
        private static final int DEFAULT_MAX_PENDING_CONNECTIONS = 50;
        private static final int DEFAULT_PORT = 8080;
        private static final @NonNull List<@NonNull Protocol> DEFAULT_PROTOCOLS =
                List.of(Protocol.HTTP_1_1, Protocol.HTTP_2);
        private static final @NonNull Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
        private static final @NonNull Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(60);

        private @Nullable NetworkProtocol networkProtocol = null;
        private final NetworkServer.@NonNull Builder networkServerBuilder;
        private int port;
        private boolean protocolNegotiationEnabled;
        private @NonNull List<@NonNull Protocol> protocols;
        private @NonNull Duration shutdownTimeout;

        public Builder() {
            this.networkServerBuilder = NetworkServer.builder()
                    .readTimeout(DEFAULT_TIMEOUT)
                    .writeTimeout(DEFAULT_TIMEOUT)
                    .maxPendingConnections(DEFAULT_MAX_PENDING_CONNECTIONS);
            this.port = DEFAULT_PORT;
            this.protocolNegotiationEnabled = true;
            this.protocols = DEFAULT_PROTOCOLS;
            this.shutdownTimeout = DEFAULT_SHUTDOWN_TIMEOUT;
        }

        @Override
        public @NonNull Builder networkProtocol(final @NonNull NetworkProtocol networkProtocol) {
            Objects.requireNonNull(networkProtocol);
            this.networkProtocol = networkProtocol;
            return this;
        }

        @Override
        public @NonNull Builder port(final int port) {
            if (port < 0 || port > 0xFFFF) {
                throw new IllegalArgumentException("Port value out of range: " + port);
            }
            this.port = port;
            return this;
        }

        @Override
        public @NonNull Builder protocols(final @NonNull List<@NonNull Protocol> protocols) {
            Objects.requireNonNull(protocols);
            this.protocols = Utils.validateProtocols(protocols);
            return this;
        }

        @Override
        public @NonNull Builder maxPendingConnections(final int maxPendingConnections) {
            if (maxPendingConnections < 0) {
                throw new IllegalArgumentException("maxPendingConnections < 0: " + maxPendingConnections);
            }
            networkServerBuilder.maxPendingConnections(maxPendingConnections);
            return this;
        }

        @Override
        public @NonNull Builder protocolNegotiationEnabled(final boolean protocolNegotiationEnabled) {
            this.protocolNegotiationEnabled = protocolNegotiationEnabled;
            return this;
        }

        @Override
        public @NonNull Builder readTimeout(final @NonNull Duration readTimeout) {
            Utils.checkDuration("readTimeout", readTimeout);
            networkServerBuilder.readTimeout(readTimeout);
            return this;
        }

        @Override
        public @NonNull Builder useNio(final boolean useNio) {
            networkServerBuilder.useNio(useNio);
            return this;
        }

        @Override
        public @NonNull Builder shutdownTimeout(final @NonNull Duration shutdownTimeout) {
            Utils.checkDuration("shutdownTimeout", shutdownTimeout);
            this.shutdownTimeout = shutdownTimeout;
            return this;
        }

        @Override
        public @NonNull Builder writeTimeout(final @NonNull Duration writeTimeout) {
            Utils.checkDuration("writeTimeout", writeTimeout);
            networkServerBuilder.writeTimeout(writeTimeout);
            return this;
        }

        @Override
        public @NonNull JayoHttpServer build() {
            return new RealJayoHttpServer(this);
        }
    }
}
