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

package jayo.http;

import jayo.http.internal.RealConnectionSpec;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.net.ssl.SSLEngine;
import java.util.List;
import java.util.Objects;

/**
 * Specifies configuration for the socket connection that HTTP traffic travels through. For {@code https:} URLs, this
 * includes the TLS version and cipher suites to use when negotiating a secure connection.
 * <p>
 * The TLS versions configured in a connection spec are only be used if they are also enabled in the SSL engine.
 * For example, if a SSL engine does not have TLS 1.3 enabled, it will not be used even if it is present on the
 * connection spec. The same policy also applies to cipher suites.
 * <p>
 * Use {@link Builder#enableAllTlsVersions(boolean)} and {@link Builder#enableAllCipherSuites(boolean)} to defer all
 * feature selection to the underlying SSL engine.
 */
public sealed interface ConnectionSpec permits RealConnectionSpec {
    static @NonNull Builder builder(final @NonNull ConnectionSpec connectionSpec) {
        Objects.requireNonNull(connectionSpec);
        if (!(connectionSpec instanceof RealConnectionSpec realConnectionSpec)) {
            throw new IllegalArgumentException("connectionSpec must be a RealConnectionSpec");
        }
        return new RealConnectionSpec.Builder(realConnectionSpec);
    }

    /**
     * A secure TLS connection that requires a recent client platform and a recent server.
     */
    @NonNull
    ConnectionSpec MODERN_TLS = RealConnectionSpec.MODERN_TLS;

    /**
     * An efficient TLS configuration that works on most client platforms and can connect to most servers.
     * This is Jayo HTTP's default configuration.
     */
    @NonNull
    ConnectionSpec COMPATIBLE_TLS = RealConnectionSpec.COMPATIBLE_TLS;

    /**
     * A backwards-compatible fallback configuration that works on obsolete client platforms and can connect to obsolete
     * servers. When possible, prefer to upgrade your client platform or server rather than using this configuration.
     */
    @NonNull
    ConnectionSpec LEGACY_TLS = RealConnectionSpec.LEGACY_TLS;

    /**
     * Unencrypted, unauthenticated connections for {@code http:} URLs.
     */
    @NonNull
    ConnectionSpec CLEARTEXT = RealConnectionSpec.CLEARTEXT;

    boolean isTls();

    /**
     * @return the cipher suites to use for a connection. Returns null if all the SSL engine's enabled cipher suites
     * should be used.
     */
    @Nullable
    List<CipherSuite> getCipherSuites();

    /**
     * @return the TLS versions to use when negotiating a connection. Returns null if all the SSL engine's enabled TLS
     * versions should be used.
     */
    @Nullable
    List<TlsVersion> getTlsVersions();

    /**
     * @return {@code true} if the SSL engine, as currently configured, supports this connection spec. In order for a
     * SSL engine to be compatible the enabled cipher suites and protocols must intersect.
     * <p>
     * For cipher suites, at least one of the {@linkplain #getCipherSuites() required cipher suites} must match the
     * SSL engine's enabled cipher suites. If there are no required cipher suites the SSL engine must have at least
     * one cipher suite enabled.
     * <p>
     * For protocols, at least one of the {@linkplain #getTlsVersions() required protocols} must match the TLS
     * endpoint's enabled protocols.
     */
    boolean isCompatible(final @NonNull SSLEngine sslEngine);

    /**
     * The builder used to create a {@link ConnectionSpec} instance.
     */
    sealed interface Builder permits RealConnectionSpec.Builder {
        @NonNull
        Builder enableAllCipherSuites(final boolean enableAllCipherSuites);

        @NonNull
        Builder cipherSuites(final @NonNull CipherSuite @NonNull ... cipherSuites);

        @NonNull
        Builder cipherSuites(final @NonNull String @NonNull ... cipherSuites);

        @NonNull
        Builder enableAllTlsVersions(final boolean enableAllTlsVersions);

        @NonNull
        Builder tlsVersions(final @NonNull TlsVersion @NonNull ... tlsVersions);

        @NonNull
        Builder tlsVersions(final @NonNull String @NonNull ... tlsVersions);

        @NonNull
        ConnectionSpec build();
    }
}
