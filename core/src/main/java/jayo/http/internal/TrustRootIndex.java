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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.security.auth.x500.X500Principal;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A simple index that of trusted root certificates that have been loaded into memory.
 */
final class TrustRootIndex {
    private final @NonNull Map<X500Principal, Set<X509Certificate>> subjectToCaCerts;

    TrustRootIndex(final @NonNull X509Certificate @NonNull ... caCerts) {
        final var map = new HashMap<X500Principal, Set<X509Certificate>>();
        for (final var caCert : caCerts) {
            map.computeIfAbsent(caCert.getSubjectX500Principal(), unused -> new HashSet<>()).add(caCert);
        }
        this.subjectToCaCerts = map;
    }

    /**
     * @return the trusted CA certificate that signed {@code cert}.
     */
    @Nullable
    X509Certificate findByIssuerAndSignature(final @NonNull X509Certificate cert) {
        final var issuer = cert.getIssuerX500Principal();
        final var subjectCaCerts = subjectToCaCerts.get(issuer);
        if (subjectCaCerts == null) {
            return null;
        }

        return subjectCaCerts.stream()
                .filter(subjectCaCert -> {
                    try {
                        cert.verify(subjectCaCert.getPublicKey());
                        return true;
                    } catch (GeneralSecurityException e) {
                        return false;
                    }
                })
                .findFirst().orElse(null);
    }

    @Override
    public boolean equals(final @Nullable Object other) {
        if (!(other instanceof TrustRootIndex that)) {
            return false;
        }

        return subjectToCaCerts.equals(that.subjectToCaCerts);
    }

    @Override
    public int hashCode() {
        return subjectToCaCerts.hashCode();
    }
}
