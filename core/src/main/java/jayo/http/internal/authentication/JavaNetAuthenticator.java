/*
 * Copyright (c) 2025-present, pull-vert and Jayo contributors.
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

package jayo.http.internal.authentication;

import jayo.JayoException;
import jayo.JayoProtocolException;
import jayo.http.*;
import jayo.network.Proxy;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.net.Authenticator;
import java.net.InetAddress;
import java.net.PasswordAuthentication;
import java.net.UnknownHostException;

import static jayo.http.internal.authentication.AuthenticationUtils.addCredentialHeader;

/**
 * Adapts {@link java.net.Authenticator} to {@link jayo.http.Authenticator}. Configure Jayo HTTP to use
 * {@link java.net.Authenticator} with {@link JayoHttpClient.Builder#authenticator(jayo.http.Authenticator)} or
 * {@link JayoHttpClient.Builder#proxyAuthenticator(jayo.http.Authenticator)}.
 */
public enum JavaNetAuthenticator implements jayo.http.Authenticator {
    INSTANCE;

    @Override
    public @Nullable ClientRequest authenticate(final @Nullable Route route, final @NonNull ClientResponse response) {
        assert response != null;

        final var challenges = response.challenges();
        final var request = response.getRequest();
        final var url = request.getUrl();
        final var proxyAuthorization = response.getStatus().code() == 407;
        final var proxy = (route != null) ? route.getAddress().getProxy() : null;

        for (final var challenge : challenges) {
            if (!"Basic".equalsIgnoreCase(challenge.getScheme())) {
                continue;
            }

            final var dns = (route != null) ? route.getAddress().getDns() : Dns.SYSTEM;
            final PasswordAuthentication auth;

            if (proxyAuthorization) {
                if (!(proxy instanceof Proxy.Http)) {
                    throw new JayoProtocolException("Received HTTP_PROXY_AUTH (407) code while not using proxy");
                }
                auth = Authenticator.requestPasswordAuthentication(
                        proxy.getHost(),
                        connectToInetAddress(proxy, url, dns),
                        proxy.getPort(),
                        url.getScheme(),
                        challenge.getRealm(),
                        challenge.getScheme(),
                        url.toUrl(),
                        Authenticator.RequestorType.PROXY
                );
            } else {
                auth = Authenticator.requestPasswordAuthentication(
                        url.getHost(),
                        connectToInetAddress(proxy, url, dns),
                        url.getPort(),
                        url.getScheme(),
                        challenge.getRealm(),
                        challenge.getScheme(),
                        url.toUrl(),
                        Authenticator.RequestorType.SERVER
                );
            }

            if (auth != null) {
                return addCredentialHeader(
                        request,
                        auth.getUserName(),
                        auth.getPassword(),
                        challenge.getCharset(),
                        proxyAuthorization);
            }
        }

        return null; // No challenges were satisfied!
    }

    private InetAddress connectToInetAddress(final @Nullable Proxy proxy,
                                             final @NonNull HttpUrl url,
                                             final @NonNull Dns dns) {
        if (proxy == null) {
            return dns.lookup(url.getHost()).get(0);
        }
        try {
            return InetAddress.getByName(proxy.getHost());
        } catch (UnknownHostException e) {
            throw JayoException.buildJayoException(e);
        }
    }
}
