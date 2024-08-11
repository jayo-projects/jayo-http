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

import jayo.http.CipherSuite;
import jayo.http.ConnectionSpec;
import jayo.http.TlsVersion;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.net.ssl.SSLEngine;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static java.util.Arrays.copyOf;
import static java.util.Comparator.naturalOrder;
import static jayo.http.internal.RealCipherSuite.ORDER_BY_NAME;

public final class RealConnectionSpec implements ConnectionSpec {
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
    public boolean equals(final Object other) {
        if (!(other instanceof RealConnectionSpec _other)) {
            return false;
        }
        if (_other == this) {
            return true;
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
            // 2) OkHttp Client - based on MODERN_TLS source code ordering
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

    public static final class Builder implements ConnectionSpec.Builder {
        private boolean tls;
        private @NonNull String @Nullable [] cipherSuites = null;
        private @NonNull String @Nullable [] tlsVersions = null;

        private Builder(final boolean tls) {
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
            Objects.requireNonNull(cipherSuites);
            if (!tls) {
                throw new IllegalArgumentException("no cipher suites for cleartext connections");
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
            return cipherSuites(strings);
        }

        @Override
        public @NonNull Builder tlsVersions(final @NonNull String @NonNull ... tlsVersions) {
            if (!tls) {
                throw new IllegalArgumentException("no TLS versions for cleartext connections");
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
