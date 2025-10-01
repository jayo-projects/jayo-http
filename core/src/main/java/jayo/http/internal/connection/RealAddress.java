/*
 * Copyright (c) 2025-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 *
 * Forked from OkHttp (https://github.com/square/okhttp), original copyright is below
 *
 * Copyright (C) 2012 The Android Open Source Project
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
import jayo.network.NetworkSocket;
import jayo.network.Proxy;
import jayo.tls.ClientTlsSocket;
import jayo.tls.Protocol;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.net.ssl.HostnameVerifier;
import java.util.List;
import java.util.Objects;

public final class RealAddress implements Address {
    private final @NonNull HttpUrl url;
    private final @NonNull Dns dns;
    private final NetworkSocket.@NonNull Builder networkSocketBuilder;
    private final ClientTlsSocket.@Nullable Builder clientTlsSocketBuilder;
    private final @Nullable HostnameVerifier hostnameVerifier;
    private final @Nullable CertificatePinner certificatePinner;
    private final @NonNull List<@NonNull Protocol> protocols;
    private final @NonNull List<ConnectionSpec> connectionSpecs;
    private final @Nullable Proxy proxy;
    private final @NonNull Authenticator proxyAuthenticator;

    RealAddress(final @NonNull String uriHost,
                final int uriPort,
                final @NonNull Dns dns,
                final NetworkSocket.@NonNull Builder networkSocketBuilder,
                final ClientTlsSocket.@Nullable Builder clientTlsSocketBuilder,
                final @Nullable HostnameVerifier hostnameVerifier,
                final @Nullable CertificatePinner certificatePinner,
                final @NonNull List<@NonNull Protocol> protocols,
                final @NonNull List<@NonNull ConnectionSpec> connectionSpecs,
                final @Nullable Proxy proxy,
                final @NonNull Authenticator proxyAuthenticator) {
        assert uriHost != null;
        assert uriPort > 0;
        assert dns != null;
        assert networkSocketBuilder != null;
        assert protocols != null;
        assert connectionSpecs != null;
        assert proxyAuthenticator != null;

        this.url = HttpUrl.builder()
                .scheme((clientTlsSocketBuilder != null) ? "https" : "http")
                .host(uriHost)
                .port(uriPort)
                .build();
        this.dns = dns;
        this.networkSocketBuilder = networkSocketBuilder;
        this.clientTlsSocketBuilder = clientTlsSocketBuilder;
        this.hostnameVerifier = hostnameVerifier;
        this.certificatePinner = certificatePinner;
        this.protocols = protocols;
        this.connectionSpecs = connectionSpecs;
        this.proxy = proxy;
        this.proxyAuthenticator = proxyAuthenticator;
    }

    @Override
    public @NonNull HttpUrl getUrl() {
        return url;
    }

    @Override
    public @NonNull List<@NonNull Protocol> getProtocols() {
        return protocols;
    }

    @Override
    public NetworkSocket.@NonNull Builder getNetworkSocketBuilder() {
        return networkSocketBuilder;
    }

    @Override
    public ClientTlsSocket.@Nullable Builder getClientTlsSocketBuilder() {
        return clientTlsSocketBuilder;
    }

    @Override
    public @NonNull List<@NonNull ConnectionSpec> getConnectionSpecs() {
        return connectionSpecs;
    }

    @Override
    public @NonNull Dns getDns() {
        return dns;
    }

    @Override
    public @Nullable HostnameVerifier getHostnameVerifier() {
        return hostnameVerifier;
    }

    @Override
    public @Nullable CertificatePinner getCertificatePinner() {
        return certificatePinner;
    }

    @Override
    public @Nullable Proxy getProxy() {
        return proxy;
    }

    @Override
    public @NonNull Authenticator getProxyAuthenticator() {
        return proxyAuthenticator;
    }

    @Override
    public boolean equals(final @Nullable Object other) {
        if (!(other instanceof RealAddress that)) {
            return false;
        }

        return url.equals(that.url) &&
                equalsNonHost(that);
    }

    @Override
    public int hashCode() {
        var result = url.hashCode();
        result = 31 * result + dns.hashCode();
        result = 31 * result + proxyAuthenticator.hashCode();
        result = 31 * result + protocols.hashCode();
        result = 31 * result + connectionSpecs.hashCode();
        result = 31 * result + (proxy != null ? proxy.hashCode() : 0);
        result = 31 * result + Objects.hashCode(clientTlsSocketBuilder);
        result = 31 * result + Objects.hashCode(hostnameVerifier);
        result = 31 * result + Objects.hashCode(certificatePinner);
        return result;
    }

    boolean equalsNonHost(final @NonNull Address other) {
        assert other != null;
        final var that = (RealAddress) other;

        return this.dns.equals(that.dns) &&
                this.proxyAuthenticator.equals(that.proxyAuthenticator) &&
                this.protocols.equals(that.protocols) &&
                this.connectionSpecs.equals(that.connectionSpecs) &&
                Objects.equals(this.proxy, that.proxy) &&
                Objects.equals(this.clientTlsSocketBuilder, that.clientTlsSocketBuilder) &&
                Objects.equals(this.hostnameVerifier, that.hostnameVerifier) &&
                Objects.equals(this.certificatePinner, that.certificatePinner) &&
                this.url.getPort() == that.url.getPort();
    }

    @Override
    public @NonNull String toString() {
        return "Address{" +
                url.getHost() + ":" + url.getPort() + ", " +
                "proxy=" + (proxy != null ? proxy : "no proxy") +
                "}";
    }
}
