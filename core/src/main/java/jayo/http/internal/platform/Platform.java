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

package jayo.http.internal.platform;

import jayo.http.Protocol;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.security.*;
import java.util.Arrays;
import java.util.List;

/**
 * Access to platform-specific features.
 * <h3>Session Tickets</h3>
 * Supported via Conscrypt.
 */
public sealed class Platform permits BouncyCastlePlatform, ConscryptPlatform {
    private static volatile @NonNull Platform platform = findPlatform();

    public static @NonNull Platform get() {
        return platform;
    }

    public static void resetForTests() {
        resetForTests(findPlatform());
    }

    public static void resetForTests(final @NonNull Platform newPlatform) {
        assert newPlatform != null;

        platform = newPlatform;
    }

    public static @NonNull List<String> alpnProtocolNames(final @NonNull List<Protocol> protocols) {
        assert protocols != null;

        return protocols.stream()
                .filter(p -> !Protocol.HTTP_1_0.equals(p))
                .map(Object::toString)
                .toList();
    }

    /**
     * Attempt to match the host runtime to a capable Platform implementation.
     */
    private static @NonNull Platform findPlatform() {
        final var preferredProvider = Security.getProviders()[0].getName();

        if ("Conscrypt".equals(preferredProvider)) {
            final var conscrypt = ConscryptPlatform.buildIfSupported();

            if (conscrypt != null) {
                return conscrypt;
            }
        }

        if ("BC".equals(preferredProvider)) {
            final var bc = BouncyCastlePlatform.buildIfSupported();

            if (bc != null) {
                return bc;
            }
        }

        return new Platform();
    }

    /**
     * Prefix used on custom headers.
     */
    public final @NonNull String getPrefix() {
        return "JayoHttp";
    }

    public @NonNull SSLContext newSSLContext() throws NoSuchAlgorithmException {
        return SSLContext.getInstance("TLS");
    }

    public @NonNull X509TrustManager platformTrustManager() throws NoSuchAlgorithmException, KeyStoreException,
            NoSuchProviderException {
        final var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        final var trustManagers = factory.getTrustManagers();
        if (trustManagers.length != 1
                || !(trustManagers[0] instanceof X509TrustManager x509TrustManager)) {
            throw new IllegalStateException("Unexpected default trust managers: " + Arrays.toString(trustManagers));
        }
        return x509TrustManager;
    }

    /**
     * Configure TLS extensions on {@code sslEngine} for {@code hostname}.
     */
    public void configureTlsExtensions(
            final @NonNull SSLEngine sslEngine,
            final @Nullable String hostname,
            final @NonNull List<Protocol> protocols) {
        assert sslEngine != null;
        assert protocols != null;

        final var sslParameters = sslEngine.getSSLParameters();
        // Enable ALPN.
        final var names = alpnProtocolNames(protocols);
        sslParameters.setApplicationProtocols(names.toArray(String[]::new));
        sslEngine.setSSLParameters(sslParameters);
    }

    /**
     * Returns the negotiated protocol, or null if no protocol was negotiated.
     */
    public @Nullable String getSelectedProtocol(final @NonNull SSLEngine sslEngine) {
        assert sslEngine != null;

        try {
            final var protocol = sslEngine.getApplicationProtocol();
            // SSLEngine.getApplicationProtocol() returns "" if application protocols values will not be used. Observed
            // if you didn't specify SSLParameters.setApplicationProtocols
            return switch (protocol) {
                case null -> null;
                case "" -> null;
                default -> protocol;
            };
        } catch (UnsupportedOperationException ignored) {
            // https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/net/ssl/SSLEngine.html#getApplicationProtocol()
            return null;
        }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }
}
