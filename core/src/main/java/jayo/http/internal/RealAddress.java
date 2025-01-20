/*
 * Copyright (c) 2024-present, pull-vert and Jayo contributors.
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

package jayo.http.internal;

import jayo.external.NonNegative;
import jayo.http.*;
import jayo.tls.Protocol;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.net.ssl.HostnameVerifier;
import java.util.List;
import java.util.Objects;

public final class RealAddress implements Address {
    private final @NonNull HttpUrl url;
    private final @NonNull Dns dns;
    private final @Nullable HostnameVerifier hostnameVerifier;
    private final @Nullable CertificatePinner certificatePinner;
    private final @NonNull List<Protocol> protocols;
    private final @NonNull List<ConnectionSpec> connectionSpecs;

    RealAddress(final @NonNull String uriHost,
                final @NonNegative int uriPort,
                final @NonNull Dns dns,
                final @Nullable HostnameVerifier hostnameVerifier,
                final @Nullable CertificatePinner certificatePinner,
                final @NonNull List<Protocol> protocols,
                final @NonNull List<ConnectionSpec> connectionSpecs) {
        assert uriHost != null;
        assert uriPort > 0;
        assert dns != null;
        assert protocols != null;
        assert connectionSpecs != null;

        this.url = HttpUrl.builder()
                .scheme((hostnameVerifier != null) ? "https" : "http")
                .host(uriHost)
                .port(uriPort)
                .build();
        this.dns = dns;
        this.hostnameVerifier = hostnameVerifier;
        this.certificatePinner = certificatePinner;
        this.protocols = List.copyOf(protocols);
        this.connectionSpecs = List.copyOf(connectionSpecs);
    }

    @Override
    public @NonNull HttpUrl getUrl() {
        return url;
    }

    @Override
    public @NonNull List<Protocol> getProtocols() {
        return protocols;
    }

    @Override
    public @NonNull List<ConnectionSpec> getConnectionSpecs() {
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
    public boolean equals(final @Nullable Object other) {
        return (other instanceof RealAddress otherAsAddress) &&
                url.equals(otherAsAddress.url) &&
                equalsNonHost(otherAsAddress);
    }

    @Override
    public int hashCode() {
        var result = url.hashCode();
        result = 31 * result + dns.hashCode();
        result = 31 * result + protocols.hashCode();
        result = 31 * result + connectionSpecs.hashCode();
        result = 31 * result + Objects.hashCode(hostnameVerifier);
        result = 31 * result + Objects.hashCode(certificatePinner);
        return result;
    }

    boolean equalsNonHost(final @NonNull RealAddress that) {
        assert that != null;

        return this.dns.equals(that.dns) &&
                this.protocols.equals(that.protocols) &&
                this.connectionSpecs.equals(that.connectionSpecs) &&
                Objects.equals(this.hostnameVerifier, that.hostnameVerifier) &&
                Objects.equals(this.certificatePinner, that.certificatePinner) &&
                this.url.getPort() == that.url.getPort();
    }

    @Override
    public @NonNull String toString() {
        return "Address{" +
                url.getHost() + ":" + url.getPort() + ", " +
                "}";
    }
}
