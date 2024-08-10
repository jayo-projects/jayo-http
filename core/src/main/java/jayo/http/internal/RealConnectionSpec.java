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

import jayo.http.ConnectionSpec;
import jayo.tls.CipherSuite;
import jayo.tls.TlsVersion;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.net.ssl.SSLEngine;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static java.util.Arrays.copyOf;
import static java.util.Comparator.naturalOrder;

/**
 * Follow <a
 * href="https://developers.cloudflare.com/ssl/edge-certificates/additional-options/cipher-suites/recommendations/">
 * Cloudflare cipher suite recommendations</a>
 */
public final class RealConnectionSpec implements ConnectionSpec {
    /**
     * Offers the best security and performance, limiting your range of clients to modern devices and browsers.
     * Supports TLS 1.2 -> 1.3 cipher suites. All suites are forward-secret and support authenticated encryption (AEAD).
     */
    private static final @NonNull List<CipherSuite> MODERN_CIPHER_SUITES =
            List.of(
                    // TLSv1.3.
                    CipherSuite.TLS_AES_128_GCM_SHA256,
                    CipherSuite.TLS_AES_256_GCM_SHA384,
                    CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
                    // TLSv1.2.
                    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                    CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                    CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
                    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
            );

    /**
     * Provides broader compatibility with somewhat weaker security.
     * Supports TLS 1.2 -> 1.3 cipher suites. All suites are forward-secret.
     */
    private static final @NonNull List<CipherSuite> COMPATIBLE_CIPHER_SUITES =
            List.of(
                    // TLSv1.3.
                    CipherSuite.TLS_AES_128_GCM_SHA256,
                    CipherSuite.TLS_AES_256_GCM_SHA384,
                    CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
                    // TLSv1.2.
                    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                    CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                    CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
                    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
                    // Note that the following cipher suites are all on HTTP/2's bad cipher suites list. We'll
                    // continue to include them until better suites are commonly available.
                    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256,
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256,
                    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384,
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384
            );

    /**
     * Includes all cipher suites that Cloudflare supports today. Broadest compatibility with the weakest security.
     * Supports TLS 1.0 -> 1.3 cipher suites.
     */
    private static final @NonNull List<CipherSuite> LEGACY_CIPHER_SUITES =
            List.of(
                    // TLSv1.3.
                    CipherSuite.TLS_AES_128_GCM_SHA256,
                    CipherSuite.TLS_AES_256_GCM_SHA384,
                    CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
                    // TLSv1.0, TLSv1.1, TLSv1.2.
                    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                    CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                    CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
                    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
                    // Note that the following cipher suites are all on HTTP/2's bad cipher suites list. We'll
                    // continue to include them until better suites are commonly available.
                    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256,
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256,
                    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384,
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384,
                    // oldest cipher suites
                    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA,
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
                    CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
                    CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA256,
                    CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA,
                    CipherSuite.TLS_RSA_WITH_AES_256_GCM_SHA384,
                    CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA256,
                    CipherSuite.TLS_RSA_WITH_AES_256_CBC_SHA,
                    CipherSuite.TLS_RSA_WITH_3DES_EDE_CBC_SHA
            );

    /**
     * A secure TLS connection that requires a recent client platform and a recent server.
     */
    public static final @NonNull RealConnectionSpec MODERN_TLS =
            new Builder(true)
                    .cipherSuites(MODERN_CIPHER_SUITES.toArray(CipherSuite[]::new))
                    .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
                    .build();

    /**
     * An efficient TLS configuration that works on most client platforms and can connect to most servers.
     * This is Jayo HTTP's default configuration.
     */
    public static final @NonNull RealConnectionSpec COMPATIBLE_TLS =
            new Builder(true)
                    .cipherSuites(COMPATIBLE_CIPHER_SUITES.toArray(CipherSuite[]::new))
                    .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
                    .build();

    /**
     * A backwards-compatible fallback configuration that works on obsolete client platforms and can connect to obsolete
     * servers. When possible, prefer to upgrade your client platform or server rather than using this configuration.
     */
    public static final @NonNull RealConnectionSpec LEGACY_TLS =
            new Builder(true)
                    .cipherSuites(LEGACY_CIPHER_SUITES.toArray(CipherSuite[]::new))
                    .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0)
                    .build();

    /**
     * Unencrypted, unauthenticated connections for {@code http:} URLs.
     */
    public static final @NonNull RealConnectionSpec CLEARTEXT = new Builder(false).build();

    final boolean isTls;
    final @NonNull String @Nullable [] cipherSuitesAsString;
    final @NonNull String @Nullable [] tlsVersionsAsString;

    private RealConnectionSpec(final boolean isTls,
                               final @NonNull String @Nullable [] cipherSuitesAsString,
                               final @NonNull String @Nullable [] tlsVersionsAsString) {
        this.isTls = isTls;
        this.cipherSuitesAsString = cipherSuitesAsString;
        this.tlsVersionsAsString = tlsVersionsAsString;
    }

    @Override
    public boolean isTls() {
        return isTls;
    }

    @Override
    public @Nullable List<CipherSuite> getCipherSuites() {
        if (cipherSuitesAsString == null || cipherSuitesAsString.length == 0) {
            return null;
        }
        return Arrays.stream(cipherSuitesAsString)
                .map(CipherSuite::fromJavaName)
                .toList();
    }

    @Override
    public @Nullable List<TlsVersion> getTlsVersions() {
        if (tlsVersionsAsString == null || tlsVersionsAsString.length == 0) {
            return null;
        }
        return Arrays.stream(tlsVersionsAsString)
                .map(TlsVersion::fromJavaName)
                .toList();
    }

    @Override
    public boolean isCompatible(final @NonNull SSLEngine sslEngine) {
        Objects.requireNonNull(sslEngine);

        if (!isTls) {
            return false;
        }

        if (tlsVersionsAsString != null &&
                !Utils.hasIntersection(tlsVersionsAsString, sslEngine.getEnabledProtocols(), naturalOrder())) {
            return false;
        }

        //noinspection RedundantIfStatement
        if (cipherSuitesAsString != null &&
                !Utils.hasIntersection(cipherSuitesAsString, sslEngine.getEnabledCipherSuites(), ORDER_BY_NAME)) {
            return false;
        }

        return true;
    }

    @Override
    public boolean equals(final @Nullable Object other) {
        if (other == this) {
            return true;
        }
        if (!(other instanceof RealConnectionSpec _other)) {
            return false;
        }

        if (this.isTls != _other.isTls) {
            return false;
        }

        if (isTls) {
            if (!Arrays.equals(this.cipherSuitesAsString, _other.cipherSuitesAsString)) {
                return false;
            }
            if (!Arrays.equals(this.tlsVersionsAsString, _other.tlsVersionsAsString)) {
                return false;
            }
        }

        return true;
    }

    @Override
    public int hashCode() {
        var result = 17;
        if (isTls) {
            result = 31 * result + Arrays.hashCode(cipherSuitesAsString);
            result = 31 * result + Arrays.hashCode(tlsVersionsAsString);
        }
        return result;
    }

    @Override
    public @NonNull String toString() {
        if (!isTls) {
            return "ConnectionSpec()";
        }

        return
                "ConnectionSpec(" +
                        "cipherSuites=" + Objects.toString(getCipherSuites(), "[all enabled]") + ", " +
                        "tlsVersions=" + Objects.toString(getTlsVersions(), "[all enabled]") + ")";
    }

    /**
     * Applies this spec to {@code sslEngine}.
     */
    void apply(final @NonNull SSLEngine sslEngine, final boolean isFallback) {
        assert sslEngine != null;

        final var specToApply = supportedSpec(sslEngine, isFallback);

        if (specToApply.tlsVersionsAsString != null) {
            sslEngine.setEnabledProtocols(specToApply.tlsVersionsAsString);
        }

        if (specToApply.cipherSuitesAsString != null) {
            sslEngine.setEnabledCipherSuites(specToApply.cipherSuitesAsString);
        }
    }

    /**
     * @return a copy of this that omits cipher suites and TLS versions not enabled by {@code sslEngine}.
     */
    private @NonNull RealConnectionSpec supportedSpec(final @NonNull SSLEngine sslEngine, final boolean isFallback) {
        final var socketEnabledCipherSuites = sslEngine.getEnabledCipherSuites();
        String[] cipherSuitesIntersection = effectiveCipherSuites(this, socketEnabledCipherSuites);

        final String[] tlsVersionsIntersection;
        if (tlsVersionsAsString != null) {
            tlsVersionsIntersection = Utils.intersect(sslEngine.getEnabledProtocols(), tlsVersionsAsString,
                    naturalOrder());
        } else {
            tlsVersionsIntersection = sslEngine.getEnabledProtocols();
        }

        // In accordance with https://tools.ietf.org/html/draft-ietf-tls-downgrade-scsv-00 the SCSV cipher is added to
        // signal that a protocol fallback has taken place.
        final var supportedCipherSuites = sslEngine.getSupportedCipherSuites();
        final var indexOfFallbackScsv = indexOfTlsFallbackScsv(supportedCipherSuites);
        if (isFallback && indexOfFallbackScsv != -1) {
            cipherSuitesIntersection =
                    concat(cipherSuitesIntersection, supportedCipherSuites[indexOfFallbackScsv]);
        }

        return new Builder(this)
                .cipherSuites(cipherSuitesIntersection)
                .tlsVersions(tlsVersionsIntersection)
                .build();
    }

    static @NonNull String @NonNull [] effectiveCipherSuites(final @NonNull RealConnectionSpec connectionSpec,
                                                             final @NonNull String @NonNull [] enabledCipherSuites) {
        if (connectionSpec.cipherSuitesAsString != null) {
            // 3 options here for ordering
            // 1) Legacy Platform - based on the Platform/Provider existing ordering in
            // sslSocket.enabledCipherSuites
            // 2) Jayo HTTP Client - based on MODERN_TLS source code ordering
            // 3) Caller specified but assuming the visible defaults in MODERN_CIPHER_SUITES are rejigged
            // to match legacy i.e. the platform/provider
            //
            // Opting for 2 here and keeping MODERN_TLS in line with secure browsers.
            return Utils.intersect(connectionSpec.cipherSuitesAsString, enabledCipherSuites, ORDER_BY_NAME);
        }
        return enabledCipherSuites;
    }

    /**
     * @return the index of the given item from the array if it exists, else return -1.
     */
    private static int indexOfTlsFallbackScsv(final @NonNull String @NonNull [] array) {
        for (int i = 0; i < array.length; i++) {
            if (ORDER_BY_NAME.compare("TLS_FALLBACK_SCSV", array[i]) == 0) {
                return i;
            }
        }
        return -1;
    }

    private static @NonNull String @NonNull [] concat(final @NonNull String @NonNull [] array,
                                                      final @NonNull String value) {
        final var result = copyOf(array, array.length + 1);
        result[result.length - 1] = value;
        return result;
    }

    /**
     * Compares cipher suites names like "TLS_RSA_WITH_NULL_MD5" and "SSL_RSA_WITH_NULL_MD5",
     * ignoring the "TLS_" or "SSL_" prefix which is not consistent across platforms. In particular
     * some IBM JVMs use the "SSL_" prefix everywhere whereas Oracle JVMs mix "TLS_" and "SSL_".
     */
    private static final Comparator<String> ORDER_BY_NAME = (a, b) -> {
        assert a != null;
        assert b != null;
        var i = 4;
        final var limit = Math.min(a.length(), b.length());
        while (i < limit) {
            final var charA = a.charAt(i);
            final var charB = b.charAt(i);
            if (charA != charB) {
                return (charA < charB) ? -1 : 1;
            }
            i++;
        }
        final var lengthA = a.length();
        final var lengthB = b.length();
        if (lengthA != lengthB) {
            return (lengthA < lengthB) ? -1 : 1;
        }
        return 0;
    };

    public static final class Builder implements ConnectionSpec.Builder {
        private final boolean tls;
        private @NonNull String @Nullable [] cipherSuites = null;
        private @NonNull String @Nullable [] tlsVersions = null;

        Builder(final boolean tls) {
            this.tls = tls;
        }

        public Builder(final @NonNull RealConnectionSpec connectionSpec) {
            Objects.requireNonNull(connectionSpec);

            this.tls = connectionSpec.isTls;
            this.cipherSuites = connectionSpec.cipherSuitesAsString;
            this.tlsVersions = connectionSpec.tlsVersionsAsString;
        }

        @Override
        public @NonNull Builder enableAllCipherSuites(final boolean enableAllCipherSuites) {
            if (!enableAllCipherSuites) {
                return this;
            }

            if (!tls) {
                throw new IllegalArgumentException("no cipher suites for cleartext connections");
            }
            this.cipherSuites = null;
            return this;
        }

        @Override
        public @NonNull Builder cipherSuites(final @NonNull CipherSuite @NonNull ... cipherSuites) {
            Objects.requireNonNull(cipherSuites);
            if (!tls) {
                throw new IllegalArgumentException("no cipher suites for cleartext connections");
            }
            final var strings = Arrays.stream(cipherSuites)
                    .map(CipherSuite::getJavaName)
                    .toArray(String[]::new);
            return cipherSuites(strings);
        }

        @Override
        public @NonNull Builder cipherSuites(final @NonNull String @NonNull ... cipherSuites) {
            if (!tls) {
                throw new IllegalArgumentException("no cipher suites for cleartext connections");
            }
            if (cipherSuites == null || cipherSuites.length == 0) {
                throw new IllegalArgumentException("At least one cipher suite is required");
            }
            this.cipherSuites = Arrays.copyOf(cipherSuites, cipherSuites.length);
            return this;
        }

        @Override
        public @NonNull Builder enableAllTlsVersions(final boolean enableAllTlsVersions) {
            if (!enableAllTlsVersions) {
                return this;
            }

            if (!tls) {
                throw new IllegalArgumentException("no TLS versions for cleartext connections");
            }
            this.tlsVersions = null;
            return this;
        }

        @Override
        public @NonNull Builder tlsVersions(final @NonNull TlsVersion @NonNull ... tlsVersions) {
            Objects.requireNonNull(tlsVersions);
            if (!tls) {
                throw new IllegalArgumentException("no TLS versions for cleartext connections");
            }
            final var strings = Arrays.stream(tlsVersions)
                    .map(TlsVersion::getJavaName)
                    .toArray(String[]::new);
            return tlsVersions(strings);
        }

        @Override
        public @NonNull Builder tlsVersions(final @NonNull String @NonNull ... tlsVersions) {
            if (!tls) {
                throw new IllegalArgumentException("no TLS versions for cleartext connections");
            }
            if (tlsVersions == null || tlsVersions.length == 0) {
                throw new IllegalArgumentException("At least one TLS version is required");
            }
            this.tlsVersions = Arrays.copyOf(tlsVersions, tlsVersions.length);
            return this;
        }

        @Override
        public @NonNull RealConnectionSpec build() {
            return new RealConnectionSpec(tls, cipherSuites, tlsVersions);
        }
    }
}
