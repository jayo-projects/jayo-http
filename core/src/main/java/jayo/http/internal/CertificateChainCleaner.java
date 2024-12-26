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

import jayo.tls.JayoTlsPeerUnverifiedException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.net.ssl.X509TrustManager;
import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes the effective certificate chain from the raw array returned by Java's built in TLS APIs. Cleaning a chain
 * returns a list of certificates where the first element is {@code chain.getFirst()}, each certificate is signed by the
 * certificate that follows, and the last certificate is a trusted CA certificate.
 * <p>
 * Use of the chain cleaner is necessary to omit unexpected certificates that aren't relevant to the TLS handshake and
 * to extract the trusted CA certificate for the benefit of certificate pinning.
 */
final class CertificateChainCleaner {
    private static final int MAX_SIGNERS = 9;

    private final TrustRootIndex trustRootIndex;

    CertificateChainCleaner(final @NonNull X509TrustManager trustManager) {
        this(trustManager.getAcceptedIssuers());
    }

    CertificateChainCleaner(final @NonNull X509Certificate @NonNull ... caCerts) {
        assert caCerts != null;

        this.trustRootIndex = new TrustRootIndex(caCerts);
    }

    /**
     * @return a cleaned chain for {@code chain}.
     * <p>
     * This method throws if the complete chain to a trusted CA certificate cannot be constructed.
     * This is unexpected unless the trust root index in this class has a different trust manager than what was used to
     * establish {@code chain}.
     */
    @NonNull
    List<Certificate> clean(final @NonNull List<Certificate> chain) {
        final List<Certificate> chainCopy = new ArrayList<>(chain);
        final List<Certificate> result = new ArrayList<>();
        result.add(chainCopy.remove(0));
        var foundTrustedCertificate = false;

        followIssuerChain:
        for (var c = 0; c < MAX_SIGNERS; c++) {
            final var toVerify = (X509Certificate) result.get(result.size() - 1);

            // If this cert has been signed by a trusted cert, use that. Add the trusted certificate to
            // the end of the chain unless it's already present. (That would happen if the first
            // certificate in the chain is itself a self-signed and trusted CA certificate.)
            final var trustedCert = trustRootIndex.findByIssuerAndSignature(toVerify);
            if (trustedCert != null) {
                if (result.size() > 1 || !toVerify.equals(trustedCert)) {
                    result.add(trustedCert);
                }
                if (verifySignature(trustedCert, trustedCert, result.size() - 2)) {
                    return result; // The self-signed cert is a root CA. We're done.
                }
                foundTrustedCertificate = true;
                continue;
            }

            // Search for the certificate in the chain that signed this certificate. This is typically
            // the next element in the chain, but it could be any element.
            final var i = chainCopy.iterator();
            while (i.hasNext()) {
                final var signingCert = (X509Certificate) i.next();
                if (verifySignature(toVerify, signingCert, result.size() - 1)) {
                    i.remove();
                    result.add(signingCert);
                    continue followIssuerChain;
                }
            }

            // We've reached the end of the chain. If any cert in the chain is trusted, we're done.
            if (foundTrustedCertificate) {
                return result;
            }

            // The last link isn't trusted. Fail.
            throw new JayoTlsPeerUnverifiedException("Failed to find a trusted cert that signed " + toVerify);
        }

        throw new JayoTlsPeerUnverifiedException("Certificate chain too long: " + result);
    }

    /**
     * Returns true if [toVerify] was signed by [signingCert]'s public key.
     *
     * @param minIntermediates the minimum number of intermediate certificates in [signingCert]. This
     *                         is -1 if signing cert is a lone self-signed certificate.
     */
    private boolean verifySignature(final @NonNull X509Certificate toVerify,
                                    final @NonNull X509Certificate signingCert,
                                    final int minIntermediates) {
        if (!toVerify.getIssuerX500Principal().equals(signingCert.getSubjectX500Principal())) {
            return false;
        }
        if (signingCert.getBasicConstraints() < minIntermediates) {
            return false; // The signer can't have this many intermediates beneath it.
        }
        try {
            toVerify.verify(signingCert.getPublicKey());
            return true;
        } catch (GeneralSecurityException verifyFailed) {
            return false;
        }
    }

    @Override
    public boolean equals(final @Nullable Object other) {
        return other == this ||
                (other instanceof CertificateChainCleaner that && trustRootIndex.equals(that.trustRootIndex));
    }

    @Override
    public int hashCode() {
        return trustRootIndex.hashCode();
    }
}
