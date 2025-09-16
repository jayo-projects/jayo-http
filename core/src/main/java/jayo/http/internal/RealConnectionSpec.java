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
import jayo.tls.ClientTlsSocket;
import jayo.tls.Protocol;
import jayo.tls.TlsVersion;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.*;

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
    private static final @NonNull List<@NonNull CipherSuite> MODERN_CIPHER_SUITES =
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
    private static final @NonNull List<@NonNull CipherSuite> COMPATIBLE_CIPHER_SUITES =
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
    private static final @NonNull List<@NonNull CipherSuite> LEGACY_CIPHER_SUITES =
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
                    .cipherSuites(MODERN_CIPHER_SUITES)
                    .tlsVersions(List.of(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2))
                    .build();

    /**
     * An efficient TLS configuration that works on most client platforms and can connect to most servers.
     * This is Jayo HTTP's default configuration.
     */
    public static final @NonNull RealConnectionSpec COMPATIBLE_TLS =
            new Builder(true)
                    .cipherSuites(COMPATIBLE_CIPHER_SUITES)
                    .tlsVersions(List.of(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2))
                    .build();

    /**
     * A backwards-compatible fallback configuration that works on obsolete client platforms and can connect to obsolete
     * servers. When possible, prefer to upgrade your client platform or server rather than using this configuration.
     */
    public static final @NonNull RealConnectionSpec LEGACY_TLS =
            new Builder(true)
                    .cipherSuites(LEGACY_CIPHER_SUITES)
                    .tlsVersions(List.of(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2, TlsVersion.TLS_1_1, TlsVersion.TLS_1_0))
                    .build();

    /**
     * Unencrypted, unauthenticated connections for {@code http:} URLs.
     */
    public static final @NonNull RealConnectionSpec CLEARTEXT = new Builder(false).build();

    final boolean isTls;
    final @Nullable List<@NonNull CipherSuite> cipherSuites;
    final @Nullable List<@NonNull TlsVersion> tlsVersions;

    private RealConnectionSpec(final boolean isTls,
                               final @Nullable List<@NonNull CipherSuite> cipherSuites,
                               @Nullable List<@NonNull TlsVersion> tlsVersions) {
        this.isTls = isTls;
        this.cipherSuites = cipherSuites;
        this.tlsVersions = tlsVersions;
    }

    @Override
    public boolean isTls() {
        return isTls;
    }

    @Override
    public @Nullable List<@NonNull CipherSuite> getCipherSuites() {
        return cipherSuites;
    }

    @Override
    public @Nullable List<@NonNull TlsVersion> getTlsVersions() {
        return tlsVersions;
    }

    @Override
    public boolean isCompatible(final ClientTlsSocket.@NonNull Parameterizer tlsParameterizer) {
        Objects.requireNonNull(tlsParameterizer);

        if (!isTls) {
            return false;
        }

        if (tlsVersions != null && Collections.disjoint(tlsVersions, tlsParameterizer.getEnabledTlsVersions())) {
            return false;
        }

        //noinspection RedundantIfStatement
        if (cipherSuites != null && Collections.disjoint(cipherSuites, tlsParameterizer.getEnabledCipherSuites())) {
            return false;
        }

        return true;
    }

    @Override
    public boolean equals(final @Nullable Object other) {
        if (!(other instanceof RealConnectionSpec that)) {
            return false;
        }

        if (this.isTls != that.isTls) {
            return false;
        }

        return !isTls ||
                (Objects.equals(this.cipherSuites, that.cipherSuites) &&
                        Objects.equals(this.tlsVersions, that.tlsVersions));
    }

    @Override
    public int hashCode() {
        var result = 17;
        if (isTls) {
            result = 31 * result + Objects.hashCode(cipherSuites);
            result = 31 * result + Objects.hashCode(tlsVersions);
        }
        return result;
    }

    @Override
    public @NonNull String toString() {
        if (!isTls) {
            return "ConnectionSpec()";
        }

        return "ConnectionSpec(" +
                "cipherSuites=" + Objects.toString(cipherSuites, "[all enabled]") + ", " +
                "tlsVersions=" + Objects.toString(tlsVersions, "[all enabled]") + ")";
    }

    /**
     * Applies this spec to {@code sslEngine} and the given {@code routeProtocols}.
     */
    public void apply(final ClientTlsSocket.@NonNull Parameterizer tlsParameterizer,
                      final boolean isFallback,
                      final @NonNull List<@NonNull Protocol> routeProtocols) {
        assert tlsParameterizer != null;
        assert routeProtocols != null;

        final var specToApply = supportedSpec(tlsParameterizer, isFallback);

        if (specToApply.tlsVersions != null) {
            tlsParameterizer.setEnabledTlsVersions(specToApply.tlsVersions);
        }

        if (specToApply.cipherSuites != null) {
            tlsParameterizer.setEnabledCipherSuites(specToApply.cipherSuites);
        }

        tlsParameterizer.setEnabledProtocols(routeProtocols);
    }

    /**
     * @return a copy of this that omits cipher suites and TLS versions not enabled by {@code sslEngine}.
     */
    @NonNull
    RealConnectionSpec supportedSpec(final ClientTlsSocket.@NonNull Parameterizer tlsParameterizer,
                                     final boolean isFallback) {
        assert tlsParameterizer != null;

        final var tlsSocketEnabledCipherSuites = tlsParameterizer.getEnabledCipherSuites();
        var cipherSuitesIntersection = effectiveCipherSuites(this, tlsSocketEnabledCipherSuites);

        final List<TlsVersion> tlsVersionsIntersection;
        if (tlsVersions != null) {
            tlsVersionsIntersection = tlsVersions.stream()
                    .filter(tlsParameterizer.getEnabledTlsVersions()::contains)
                    .toList();
        } else {
            tlsVersionsIntersection = tlsParameterizer.getEnabledTlsVersions();
        }

        // In accordance with https://tools.ietf.org/html/draft-ietf-tls-downgrade-scsv-00 the SCSV cipher is added to
        // signal that a protocol fallback has taken place.
        final var supportedCipherSuites = tlsParameterizer.getSupportedCipherSuites();
        final var indexOfFallbackScsv = indexOfTlsFallbackScsv(supportedCipherSuites);
        if (isFallback && indexOfFallbackScsv != -1) {
            cipherSuitesIntersection = concat(cipherSuitesIntersection, supportedCipherSuites.get(indexOfFallbackScsv));
        }

        return new Builder(this)
                .cipherSuites(cipherSuitesIntersection)
                .tlsVersions(tlsVersionsIntersection)
                .build();
    }

    static @NonNull List<@NonNull CipherSuite> effectiveCipherSuites(
            final @NonNull RealConnectionSpec connectionSpec,
            final @NonNull List<@NonNull CipherSuite> enabledCipherSuites) {
        assert connectionSpec != null;
        assert enabledCipherSuites != null;

        if (connectionSpec.cipherSuites != null) {
            // 3 options here for ordering
            // 1) Legacy Platform - based on the Platform/Provider existing ordering in sslSocket.enabledCipherSuites
            // 2) Jayo HTTP Client - based on MODERN_TLS source code ordering
            // 3) Caller specified but assuming the visible defaults in MODERN_CIPHER_SUITES are rejigged to match
            // legacy i.e. the platform/provider
            //
            // Opting for 2 here and keeping MODERN_TLS in line with secure browsers.
            return intersect(connectionSpec.cipherSuites, enabledCipherSuites);
        }
        return enabledCipherSuites;
    }

    /**
     * @return the index of the given item from the list if it exists, else return -1.
     */
    private static int indexOfTlsFallbackScsv(final @NonNull List<@NonNull CipherSuite> cipherSuites) {
        for (var i = 0; i < cipherSuites.size(); i++) {
            if (ORDER_BY_NAME.compare(CipherSuite.TLS_FALLBACK_SCSV.getJavaName(),
                    cipherSuites.get(i).getJavaName()) == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Concat a single value to an unmodifiable list, and return it as a new unmodifiable list.
     */
    private static @NonNull List<@NonNull CipherSuite> concat(final @NonNull List<@NonNull CipherSuite> list,
                                                              final @NonNull CipherSuite value) {
        assert list != null;
        assert value != null;

        final var tmpList = new ArrayList<>(list);
        tmpList.add(value);
        return List.copyOf(tmpList);
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

    /**
     * @return a list containing only elements found in this array and also in {@code second}. The returned elements are
     * in the same order as in {@code first}.
     */
    private static @NonNull List<@NonNull CipherSuite> intersect(final @NonNull List<@NonNull CipherSuite> first,
                                                                 final @NonNull List<@NonNull CipherSuite> second) {
        assert first != null;
        assert second != null;

        final var result = new ArrayList<CipherSuite>();
        for (final var a : first) {
            for (final var b : second) {
                if (ORDER_BY_NAME.compare(a.getJavaName(), b.getJavaName()) == 0) {
                    result.add(a);
                    break;
                }
            }
        }
        return result;
    }

    public static final class Builder implements ConnectionSpec.Builder {
        private final boolean tls;
        private @Nullable List<@NonNull CipherSuite> cipherSuites = null;
        private @Nullable List<@NonNull TlsVersion> tlsVersions = null;

        Builder(final boolean tls) {
            this.tls = tls;
        }

        public Builder(final @NonNull RealConnectionSpec connectionSpec) {
            Objects.requireNonNull(connectionSpec);

            this.tls = connectionSpec.isTls;
            this.cipherSuites = connectionSpec.cipherSuites;
            this.tlsVersions = connectionSpec.tlsVersions;
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
        public @NonNull Builder cipherSuites(final @NonNull List<@NonNull CipherSuite> cipherSuites) {
            Objects.requireNonNull(cipherSuites);
            if (!tls) {
                throw new IllegalArgumentException("no cipher suites for cleartext connections");
            }
            if (cipherSuites.isEmpty()) {
                throw new IllegalArgumentException("At least one cipher suite is required");
            }
            this.cipherSuites = List.copyOf(cipherSuites);
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
        public @NonNull Builder tlsVersions(final @NonNull List<@NonNull TlsVersion> tlsVersions) {
            Objects.requireNonNull(tlsVersions);
            if (!tls) {
                throw new IllegalArgumentException("no TLS versions for cleartext connections");
            }
            if (tlsVersions.isEmpty()) {
                throw new IllegalArgumentException("At least one TLS version is required");
            }
            this.tlsVersions = List.copyOf(tlsVersions);
            return this;
        }

        @Override
        public @NonNull RealConnectionSpec build() {
            return new RealConnectionSpec(tls, cipherSuites, tlsVersions);
        }
    }
}
