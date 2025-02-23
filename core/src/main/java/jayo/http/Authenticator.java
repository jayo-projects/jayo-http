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

package jayo.http;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Performs either <b>preemptive</b> authentication before connecting to a proxy server, or <b>reactive</b>
 * authentication after receiving a challenge from either an origin web server or proxy server.
 * <h2>Preemptive Authentication</h2>
 * To make HTTPS calls using an HTTP proxy server, Jayo HTTP must first negotiate a connection with the proxy. This
 * proxy connection is called a "TLS Tunnel" and is specified by
 * <a href="https://tools.ietf.org/html/rfc2817">RFC 2817</a>. The HTTP CONNECT request that creates this tunnel
 * connection is special: it does not participate in any {@linkplain Interceptor interceptors} or
 * {@linkplain EventListener event listeners}. It doesn't include the motivating request's HTTP headers or even its full
 * URL; only the target server's hostname is sent to the proxy.
 * <p>
 * Before sending any CONNECT request, Jayo HTTP always calls the proxy authenticator so that it may prepare preemptive
 * authentication. Jayo HTTP will call {@link #authenticate(Route, ClientResponse)} with a fake {@code HTTP/1.1 407
 * Proxy Authentication Required} response that has a {@code Proxy-Authenticate: JayoHttp-Preemptive} challenge. The
 * proxy authenticator may return either an authenticated request or null to connect without authentication.
 * <pre>
 * {@code
 * for (Challenge challenge : response.challenges()) {
 *   // If this is preemptive auth, use a preemptive credential.
 *   if (challenge.getScheme().equalsIgnoreCase("JayoHttp-Preemptive")) {
 *     return response.getRequest().newBuilder()
 *         .header("Proxy-Authorization", "secret")
 *         .build();
 *   }
 * }
 * return null; // Didn't find a preemptive auth scheme.
 * }
 * </pre>
 * <h2>Reactive Authentication</h2>
 * Implementations authenticate by returning a follow-up request that includes an authorization header, or they may
 * decline the challenge by returning null. In this case the unauthenticated response will be returned to the caller
 * that triggered it.
 * <p>
 * Implementations should check if the initial request already included an attempt to authenticate. If so, it is likely
 * that further attempts will not be useful and the authenticator should give up.
 * <p>
 * When an origin web server requests reactive authentication, the response code is {@code 401} and the implementation
 * should respond with a new request that sets the "Authorization" header.
 * <pre>
 * {@code
 * if (response.getRequest().header("Authorization") != null) {
 *   return null; // Give up, we've already failed to authenticate.
 * }
 *
 * String credential = Credentials.basic(...)
 * return response.getRequest().newBuilder()
 *     .header("Authorization", credential)
 *     .build();
 * }
 * </pre>
 * When a proxy server requests reactive authentication, the response code is {@code 407} and the implementation should
 * respond with a new request that sets the "Proxy-Authorization" header.
 * <pre>
 * {@code
 * if (response.getRequest().header("Proxy-Authorization") != null) {
 *   return null; // Give up, we've already failed to authenticate.
 * }
 *
 * String credential = Credentials.basic(...)
 * return response.getRequest().newBuilder()
 *     .header("Proxy-Authorization", credential)
 *     .build();
 * }
 * </pre>
 * The proxy authenticator may implement preemptive authentication, reactive authentication, or both.
 * <p>
 * Applications may configure Jayo HTTP with an authenticator for origin servers, or proxy servers, or both.
 * <h2>Authentication Retries</h2>
 * If your authentication may be flaky and requires retries, you should apply some policy to limit the retries by the
 * class of errors and number of attempts. To get the number of attempts to the current point, use this function.
 * <pre>
 * {@code
 * private int responseCount(ClientResponse response) {
 *   int result = 1;
 *   while ((response = response.getPriorResponse()) != null) {
 *     result++;
 *   }
 *   return result;
 * }
 * }
 * </pre>
 */
@FunctionalInterface
public interface Authenticator {
    /**
     * @return a request that includes the credentials to satisfy an authentication challenge in {@code response}.
     * Returns null if the challenge cannot be satisfied.
     * <p>
     * The route is best-effort, it currently may not always be provided even when logically available. It may also not
     * be provided when an authenticator is re-used manually in an application interceptor, such as when implementing
     * client-specific retries.
     */
    @Nullable
    ClientRequest authenticate(final @Nullable Route route, final @NonNull ClientResponse response);


    /**
     * An authenticator that knows no credentials and makes no attempt to authenticate.
     */
    @NonNull
    Authenticator NONE = new AuthenticatorNone();

    final class AuthenticatorNone implements Authenticator {
        @Override
        public @Nullable ClientRequest authenticate(final @Nullable Route route,
                                                    final @NonNull ClientResponse response) {
            Objects.requireNonNull(response);
            return null;
        }
    }
}
