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

import jayo.ByteString;
import jayo.http.CertificatePinner;
import jayo.tls.JayoTlsPeerUnverifiedException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import static jayo.http.CertificatePinner.pin;
import static jayo.http.CertificatePinner.sha256Hash;

public final class RealCertificatePinner implements CertificatePinner {
    private final @NonNull Set<CertificatePinner.Pin> pins;
    private final @Nullable CertificateChainCleaner certificateChainCleaner;

    private RealCertificatePinner(final @NonNull Set<CertificatePinner.Pin> pins,
                                  final @Nullable CertificateChainCleaner certificateChainCleaner) {
        this.pins = Objects.requireNonNull(pins);
        this.certificateChainCleaner = certificateChainCleaner;
    }

    @Override
    public @NonNull Set<CertificatePinner.Pin> getPins() {
        return pins;
    }

    @Override
    public void check(final @NonNull String hostname, final @NonNull List<Certificate> peerCertificates) {
        Objects.requireNonNull(hostname);
        Objects.requireNonNull(peerCertificates);

        check(hostname, () -> {
            final List<Certificate> cleanedPeerCertificates;
            if (certificateChainCleaner != null) {
                cleanedPeerCertificates = certificateChainCleaner.clean(peerCertificates);
            } else {
                cleanedPeerCertificates = peerCertificates;
            }
            return cleanedPeerCertificates.stream()
                    .map(c -> ((X509Certificate) c))
                    .toList();
        });
    }

    void check(final @NonNull String hostname,
               final @NonNull Supplier<List<X509Certificate>> cleanedPeerCertificatesFn) {
        final var pins = findMatchingPins(hostname);
        if (pins.isEmpty()) {
            return;
        }

        final var peerCertificates = cleanedPeerCertificatesFn.get();

        for (final var peerCertificate : peerCertificates) {
            // Lazily compute the hash for each certificate.
            ByteString sha256 = null;

            for (final var pin : pins) {
                if (!pin.getHashAlgorithm().equals("sha256")) {
                    throw new AssertionError("unsupported hashAlgorithm: " + pin.getHashAlgorithm());
                }
                if (sha256 == null) {
                    sha256 = sha256Hash(peerCertificate);
                }
                if (pin.getHash().equals(sha256)) {
                    return; // Success!
                }
            }
        }

        // If we couldn't find a matching pin, format a nice exception.
        final var messageSb = new StringBuilder();
        messageSb.append("Certificate pinning failure!");
        messageSb.append("\n  Peer certificate chain:");
        for (final var element : peerCertificates) {
            messageSb.append("\n    ");
            messageSb.append(pin(element));
            messageSb.append(": ");
            messageSb.append(element.getSubjectX500Principal().getName());
        }
        messageSb.append("\n  Pinned certificates for ");
        messageSb.append(hostname);
        messageSb.append(":");
        for (final var pin : pins) {
            messageSb.append("\n    ");
            messageSb.append(pin);
        }
        throw new JayoTlsPeerUnverifiedException(messageSb.toString());
    }

    @Override
    public @NonNull List<CertificatePinner.Pin> findMatchingPins(final @NonNull String hostname) {
        Objects.requireNonNull(hostname);

        return pins.stream()
                .filter(pin -> pin.matchesHostname(hostname))
                .toList();
    }

    /**
     * @return a certificate pinner that uses {@code certificateChainCleaner}.
     */
    @NonNull
    RealCertificatePinner withCertificateChainCleaner(
            final @NonNull CertificateChainCleaner certificateChainCleaner) {
        assert certificateChainCleaner != null;

        if (certificateChainCleaner.equals(this.certificateChainCleaner)) {
            return this;
        }

        return new RealCertificatePinner(pins, certificateChainCleaner);
    }

    @Override
    public boolean equals(final @Nullable Object other) {
        if (!(other instanceof RealCertificatePinner _other)) {
            return false;
        }

        return pins.equals(_other.pins)
                && Objects.equals(certificateChainCleaner, _other.certificateChainCleaner);
    }

    @Override
    public int hashCode() {
        var result = 37;
        result = 41 * result + pins.hashCode();
        result = 41 * result + Objects.hashCode(certificateChainCleaner);
        return result;
    }

    public static final class Pin implements CertificatePinner.Pin {
        private final @NonNull String pattern;
        private final @NonNull String hashAlgorithm;
        private final @NonNull ByteString hash;

        public Pin(final @NonNull String pattern, final @NonNull String pin) {
            assert pattern != null;
            assert pin != null;

            if (!(
                    (pattern.startsWith("*.") && pattern.indexOf("*", 1) == -1) ||
                            (pattern.startsWith("**.") && pattern.indexOf("*", 2) == -1) ||
                            !pattern.contains("*")
            )) {
                throw new IllegalArgumentException("Unexpected pattern: " + pattern);
            }

            final var canonicalizedPattern = HostnameUtils.toCanonicalHost(pattern);
            if (canonicalizedPattern == null) {
                throw new IllegalArgumentException("Unexpected pattern: " + pattern);
            }

            this.pattern = canonicalizedPattern;

            if (!pin.startsWith("sha256/")) {
                throw new IllegalArgumentException("pins must start with 'sha256/': " + pin);
            }

            final var hash = ByteString.decodeBase64(pin.substring("sha256/".length()));
            if (hash == null) {
                throw new IllegalArgumentException("Invalid pin hash: " + pin);
            }

            this.hashAlgorithm = "sha256";
            this.hash = hash;
        }

        @Override
        public @NonNull String getPattern() {
            return pattern;
        }

        @Override
        public @NonNull String getHashAlgorithm() {
            return hashAlgorithm;
        }

        @Override
        public @NonNull ByteString getHash() {
            return hash;
        }

        @Override
        public boolean matchesHostname(final @NonNull String hostname) {
            Objects.requireNonNull(hostname);

            if (pattern.startsWith("**.")) {
                // With ** empty prefixes match so exclude the dot from regionMatches().
                final var suffixLength = pattern.length() - 3;
                final var prefixLength = hostname.length() - suffixLength;
                return hostname.regionMatches(hostname.length() - suffixLength, pattern, 3, suffixLength)
                        && (prefixLength == 0 || hostname.charAt(prefixLength - 1) == '.');
            }
            if (pattern.startsWith("*.")) {
                // With * there must be a prefix so include the dot in regionMatches().
                final var suffixLength = pattern.length() - 1;
                final var prefixLength = hostname.length() - suffixLength;
                return hostname.regionMatches(hostname.length() - suffixLength, pattern, 1, suffixLength)
                        && hostname.lastIndexOf('.', prefixLength - 1) == -1;
            }

            return hostname.equals(pattern);
        }

        @Override
        public boolean matchesCertificate(final @NonNull X509Certificate certificate) {
            Objects.requireNonNull(certificate);

            if (hashAlgorithm.equals("sha256")) {
                return hash.equals(sha256Hash(certificate));
            }
            return false;
        }

        @Override
        public @NonNull String toString() {
            return hashAlgorithm + "/" + hash.base64();
        }

        @Override
        public boolean equals(final @Nullable Object other) {
            if (!(other instanceof Pin _other)) {
                return false;
            }

            return pattern.equals(_other.pattern) && hashAlgorithm.equals(_other.hashAlgorithm) && hash.equals(_other.hash);
        }

        @Override
        public int hashCode() {
            int result = pattern.hashCode();
            result = 31 * result + hashAlgorithm.hashCode();
            result = 31 * result + hash.hashCode();
            return result;
        }
    }

    public static final class Builder implements CertificatePinner.Builder {
        private final @NonNull List<Pin> pins = new ArrayList<>();

        @Override
        public @NonNull Builder add(final @NonNull String pattern, final @NonNull String @NonNull ... pins) {
            for (final var pin : pins) {
                this.pins.add(new Pin(pattern, pin));
            }
            return this;
        }

        @Override
        public @NonNull RealCertificatePinner build() {
            return new RealCertificatePinner(Set.copyOf(pins), null);
        }
    }
}
