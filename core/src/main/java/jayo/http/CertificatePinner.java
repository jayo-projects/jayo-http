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

import jayo.ByteString;
import jayo.http.internal.RealCertificatePinner;
import org.jspecify.annotations.NonNull;

import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static jayo.crypto.JdkDigest.SHA_256;

/**
 * Constrains which certificates are trusted. Pinning certificates defends against attacks on certificate authorities.
 * It also prevents connections through man-in-the-middle certificate authorities either known or unknown to the
 * application's user. This class currently pins a certificate's Subject Public Key Info as described on
 * <a href="https://goo.gl/AIx3e5">Adam Langley's Weblog</a>. Pins are base64 SHA-256 hashes as in
 * <a href="https://tools.ietf.org/html/rfc7469">HTTP Public Key Pinning (HPKP)</a>.
 * <h2>Setting up Certificate Pinning</h2>
 * The easiest way to pin a host is turn on pinning with a broken configuration and read the expected configuration when
 * the connection fails. Be sure to do this on a trusted network, and without man-in-the-middle tools like
 * <a href="https://charlesproxy.com">Charles</a> or <a href="https://fiddlertool.com">Fiddler</a>.
 * <p>
 * For example, to pin {@code https://publicobject.com}, start with a broken configuration:
 * <pre>
 * {@code
 * String hostname = "publicobject.com";
 * CertificatePinner certificatePinner = new CertificatePinner.builder()
 * .add(hostname, "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
 * .build();
 * JayoHttpClient client = JayoHttpClient.builder()
 * .certificatePinner(certificatePinner)
 * .build();
 * <p>
 * Request request = new Request.builder()
 * .url("https://" + hostname)
 * .build();
 * client.newCall(request).execute();
 * }
 * </pre>
 * As expected, this fails with a certificate pinning exception:
 * <pre>
 * {@code
 * javax.net.ssl.SSLPeerUnverifiedException: Certificate pinning failure!
 * Peer certificate chain:
 * sha256/afwiKY3RxoMmLkuRW1l7QsPZTJPwDS2pdDROQjXw8ig=: CN=publicobject.com, OU=PositiveSSL
 * sha256/klO23nT2ehFDXCfx3eHTDRESMz3asj1muO+4aIdjiuY=: CN=COMODO RSA Secure Server CA
 * sha256/grX4Ta9HpZx6tSHkmCrvpApTQGo67CYDnvprLg5yRME=: CN=COMODO RSA Certification Authority
 * sha256/lCppFqbkrlJ3EcVFAkeip0+44VaoJUymbnOaEUk7tEU=: CN=AddTrust External CA Root
 * Pinned certificates for publicobject.com:
 * sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
 * at jayo.http.CertificatePinner.check(CertificatePinner.java)
 * at jayo.http.Connection.upgradeToTls(Connection.java)
 * at jayo.http.Connection.connect(Connection.java)
 * at jayo.http.Connection.connectAndSetOwner(Connection.java)
 * }
 * </pre>
 * Follow up by pasting the public key hashes from the exception into the certificate pinner's configuration:
 * <pre>
 * {@code
 * CertificatePinner certificatePinner = new CertificatePinner.builder()
 * .add("publicobject.com", "sha256/afwiKY3RxoMmLkuRW1l7QsPZTJPwDS2pdDROQjXw8ig=")
 * .add("publicobject.com", "sha256/klO23nT2ehFDXCfx3eHTDRESMz3asj1muO+4aIdjiuY=")
 * .add("publicobject.com", "sha256/grX4Ta9HpZx6tSHkmCrvpApTQGo67CYDnvprLg5yRME=")
 * .add("publicobject.com", "sha256/lCppFqbkrlJ3EcVFAkeip0+44VaoJUymbnOaEUk7tEU=")
 * .build();
 * }
 * </pre>
 * <h2>Domain Patterns</h2>
 * Pinning is per-hostname and/or per-wildcard pattern. To pin both {@code publicobject.com} and
 * {@code www.publicobject.com} you must configure both hostnames. Or you may use patterns to match sets of related
 * domain names. The following forms are permitted:
 * <ul>
 * <li><b>Full domain name</b>: you may pin an exact domain name like {@code www.publicobject.com}. It won't match
 * additional prefixes ({@code us-west.www.publicobject.com}) or suffixes ({@code publicobject.com}).
 * <li><b>Any number of subdomains</b>: Use two asterisks to like {@code **.publicobject.com} to match any number of
 * prefixes ({@code us-west.www.publicobject.com}, {@code www.publicobject.com}) including no prefix at all
 * ({@code publicobject.com}). For most applications this is the best way to configure certificate pinning.
 * <li><b>Exactly one subdomain</b>: Use a single asterisk like {@code *.publicobject.com} to match exactly one prefix
 * ({@code www.publicobject.com}, {@code api.publicobject.com}). Be careful with this approach as no pinning will be
 * enforced if additional prefixes are present, or if no prefixes are present.
 * </ul>
 * Note that any other form is unsupported. You may not use asterisks in any position other than the leftmost label.
 * <p>
 * If multiple patterns match a hostname, any match is sufficient. For example, suppose pin A applies to
 * {@code *.publicobject.com} and pin B applies to {@code api.publicobject.com}. Handshakes for
 * {@code api.publicobject.com} are valid if either A's or B's certificate is in the chain.
 * <h2>Warning: Certificate Pinning is Dangerous!</h2>
 * Pinning certificates limits your server team's abilities to update their TLS certificates. By pinning certificates,
 * you take on additional operational complexity and limit your ability to migrate between certificate authorities. Do
 * not use certificate pinning without the blessing of your server's TLS administrator!
 * <h3>Note about self-signed certificates</h3>
 * {@link CertificatePinner} cannot be used to pin self-signed certificate if such certificate is not accepted by
 * {@link javax.net.ssl.TrustManager}.
 *
 * @see <a href="https://www.owasp.org/index.php/Certificate_and_Public_Key_Pinning">OWASP:
 * Certificate and Public Key Pinning</a>.
 */
public sealed interface CertificatePinner permits RealCertificatePinner {
    static @NonNull Builder builder() {
        return new RealCertificatePinner.Builder();
    }

    static @NonNull ByteString sha256Hash(final @NonNull X509Certificate certificate) {
        Objects.requireNonNull(certificate);
        return ByteString.of(certificate.getPublicKey().getEncoded()).hash(SHA_256);
    }

    /**
     * @return the SHA-256 of {@code certificate}'s public key.
     */
    static @NonNull String pin(final @NonNull Certificate certificate) {
        if (!(certificate instanceof X509Certificate x509Certificate)) {
            throw new IllegalArgumentException("Certificate pinning requires X509 certificates");
        }
        return "sha256/" + sha256Hash(x509Certificate).base64();
    }

    @NonNull
    Set<Pin> getPins();

    /**
     * Confirms that at least one of the certificates pinned for {@code hostname} is in {@code peerCertificates}. Does
     * nothing if there are no certificates pinned for {@code hostname}. Jayo HTTP calls this after a successful TLS
     * handshake, but before the connection is used.
     *
     * @throws jayo.tls.JayoTlsPeerUnverifiedException if {@code peerCertificates} don't match the certificates pinned
     *                                                 for {@code hostname}.
     */
    void check(final @NonNull String hostname, final @NonNull List<Certificate> peerCertificates);

    /**
     * @return the list of matching certificates' pins for the hostname. Returns an empty list if the hostname does not
     * have pinned certificates.
     */
    @NonNull
    List<Pin> findMatchingPins(final @NonNull String hostname);

    /**
     * A hostname pattern and certificate hash for Certificate Pinning.
     */
    sealed interface Pin permits RealCertificatePinner.Pin {
        static @NonNull Pin create(final @NonNull String pattern, final @NonNull String pin) {
            Objects.requireNonNull(pattern);
            Objects.requireNonNull(pin);
            return new RealCertificatePinner.Pin(pattern, pin);
        }

        /**
         * A hostname like {@code example.com} or a pattern like {@code *.example.com} (canonical form).
         */
        @NonNull
        String getPattern();

        /**
         * Only {@code sha256} for now, the deprecated {@code sha1} algorithm is not supported.
         */
        @NonNull
        String getHashAlgorithm();

        /**
         * The hash of the pinned certificate using {@link #getHashAlgorithm()}.
         */
        @NonNull
        ByteString getHash();

        boolean matchesHostname(final @NonNull String hostname);

        boolean matchesCertificate(final @NonNull X509Certificate certificate);
    }

    /**
     * Builds a configured {@link CertificatePinner}.
     */
    sealed interface Builder permits RealCertificatePinner.Builder {
        /**
         * Pins certificates for {@code pattern}.
         *
         * @param pattern lower-case host name or wildcard pattern such as {@code *.example.com}.
         * @param pins    SHA-256 hashes. Each pin is a hash of a certificate's Subject Public Key Info, base64-encoded
         *                and prefixed with {@code sha256/}.
         */
        @NonNull
        Builder add(final @NonNull String pattern, final @NonNull String @NonNull ... pins);

        @NonNull
        CertificatePinner build();
    }
}
