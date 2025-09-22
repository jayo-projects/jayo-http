/*
 * Copyright (c) 2025-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package jayo.http.internal.authentication;

import jayo.http.*;
import jayo.network.Proxy;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import static jayo.http.internal.authentication.AuthenticationUtils.addCredentialHeader;

/**
 * Configure Jayo HTTP to authenticate to proxy by using the credentials declared in the HTTP Proxy
 * {@link Proxy.Http#getAuthentication()}.
 * <p>
 * Note: Use this authenticator only with {@link JayoHttpClient.Builder#proxyAuthenticator(jayo.http.Authenticator)}.
 */
public enum DefaultProxyAuthenticator implements Authenticator {
    INSTANCE;

    @Override
    public @Nullable ClientRequest authenticate(final @Nullable Route route, final @NonNull ClientResponse response) {
        final var request = response.getRequest();

        if (route == null || !(route.getAddress().getProxy() instanceof Proxy.Http httpProxy) || // should not happen
                request.header("Proxy-Authorization") != null) { // Give up, we've already failed to authenticate
            return null;
        }
        final var proxyAuth = httpProxy.getAuthentication(); // built on-demand, we should call it only once
        if (proxyAuth == null) { // the proxy has not auth configured
            return null;
        }


        final var challenges = response.challenges();
        for (final var challenge : challenges) {
            // If this is preemptive auth, use a preemptive credential.
            if (challenge.getScheme().equalsIgnoreCase(JAYO_PREEMPTIVE_CHALLENGE)) {
                return addCredentialHeader(
                        request,
                        proxyAuth.getUsername(),
                        proxyAuth.getPassword(),
                        proxyAuth.getCharset(),
                        true);
            }

            if (!"Basic".equalsIgnoreCase(challenge.getScheme())) {
                continue;
            }

            return addCredentialHeader(
                    request,
                    proxyAuth.getUsername(),
                    proxyAuth.getPassword(),
                    challenge.getCharset(),
                    true);
        }

        return null; // No challenges were satisfied!
    }
}
