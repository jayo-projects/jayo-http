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

package jayo.http.internal.connection;

import jayo.*;
import jayo.http.ClientRequest;
import jayo.http.ClientResponse;
import jayo.http.Interceptor;
import jayo.http.internal.UrlUtils;
import jayo.http.internal.Utils;
import jayo.http.http2.JayoConnectionShutdownException;
import jayo.http.tools.HttpMethodUtils;
import jayo.network.Proxy;
import jayo.tls.JayoTlsHandshakeException;
import jayo.tls.JayoTlsPeerUnverifiedException;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;

import static java.net.HttpURLConnection.*;
import static jayo.http.internal.Utils.stripBody;
import static jayo.http.internal.http.HttpStatusCodes.*;

/**
 * This interceptor recovers from failures and follows redirects as necessary. It may throw a {@link JayoException} if
 * the call was canceled.
 */
final class RetryAndFollowUpInterceptor implements Interceptor {
    /**
     * How many redirects and auth challenges should we attempt?
     * <ul>
     * <li>Chrome follows 21 redirects;
     * <li>Firefox, curl, and wget follow 20;
     * <li>Safari follows 16;
     * <li>and HTTP/1.0 recommends 5
     * </ul>
     */
    private static final int MAX_FOLLOW_UPS = 20;

    private final @NonNull RealJayoHttpClient client;

    RetryAndFollowUpInterceptor(final @NonNull RealJayoHttpClient client) {
        assert client != null;
        this.client = client;
    }

    @Override
    public @NonNull ClientResponse intercept(final @NonNull Chain chain) {
        assert chain != null;
        final var realChain = (RealInterceptorChain) chain;

        var request = chain.request();
        final var call = realChain.call();
        var followUpCount = 0;
        ClientResponse priorResponse = null;
        var newRoutePlanner = true;
        final var recoveredFailures = new ArrayList<JayoException>();

        while (true) {
            call.enterNetworkInterceptorExchange(request, newRoutePlanner);

            ClientResponse response;
            var closeActiveExchange = true;
            try {
                if (call.isCanceled()) {
                    throw new JayoException("Canceled");
                }

                try {
                    response = realChain.proceed(request);
                    newRoutePlanner = true;
                } catch (JayoException je) {
                    // An attempt to communicate with a server failed. The request may have been sent.
                    final var isRecoverable = recover(je, call, request);
                    call.eventListener.retryDecision(call, je, isRecoverable);
                    if (!isRecoverable) {
                        throw withSuppressed(je, recoveredFailures);
                    }
                    recoveredFailures.add(je);
                    newRoutePlanner = false;
                    continue;
                }

                // Clear out downstream interceptor's additional request headers, cookies, etc.
                response = response.newBuilder()
                        .request(request)
                        .priorResponse((priorResponse != null) ? stripBody(priorResponse).build() : null)
                        .build();

                final var exchange = call.interceptorScopedExchange();
                final var followUp = followUpRequest(response, exchange);

                if (followUp == null) {
                    if (exchange != null && exchange.isDuplex) {
                        call.timeoutEarlyExit();
                    }
                    closeActiveExchange = false;
                    call.eventListener.followUpDecision(call, response, null);
                    return response;
                }

                final var followUpBody = followUp.getBody();
                if (followUpBody != null && followUpBody.isOneShot()) {
                    closeActiveExchange = false;
                    call.eventListener.followUpDecision(call, response, null);
                    return response;
                }

                Utils.closeQuietly(response.getBody());

                if (++followUpCount > MAX_FOLLOW_UPS) {
                    call.eventListener().followUpDecision(call, response, null);
                    throw new JayoProtocolException("Too many follow-up requests: " + followUpCount);
                }

                call.eventListener.followUpDecision(call, response, followUp);
                request = followUp;
                priorResponse = response;
            } finally {
                call.exitNetworkInterceptorExchange(closeActiveExchange);
            }
        }
    }

    /**
     * Figures out the HTTP request to make in response to receiving {@code userResponse}. This will either add
     * authentication headers, follow redirects, or handle a client request timeout. If a follow-up is either
     * unnecessary or not applicable, this returns null.
     */
    private @Nullable ClientRequest followUpRequest(final @NonNull ClientResponse userResponse,
                                                    final @Nullable Exchange exchange) {
        assert userResponse != null;

        final var route = (exchange != null) ? exchange.connection().route() : null;
        final var responseCode = userResponse.getStatusCode();

        final var method = userResponse.getRequest().getMethod();
        return switch (responseCode) {
            case HTTP_PROXY_AUTH -> {
                assert route != null;
                if (!(route.getAddress().getProxy() instanceof Proxy.Http)) {
                    throw new JayoProtocolException("Received HTTP_PROXY_AUTH (407) code while not using proxy");
                }
                yield client.getProxyAuthenticator().authenticate(route, userResponse);
            }

            case HTTP_UNAUTHORIZED -> client.getAuthenticator().authenticate(route, userResponse);

            case HTTP_PERM_REDIRECT, HTTP_TEMP_REDIRECT, HTTP_MULT_CHOICE, HTTP_MOVED_PERM, HTTP_MOVED_TEMP,
                 HTTP_SEE_OTHER -> buildRedirectRequest(userResponse, method);

            case HTTP_CLIENT_TIMEOUT -> {
                // 408's are rare in practice, but some servers like HAProxy use this response code. The spec says that
                // we may repeat the request without modifications. Modern browsers also repeat the request (even
                // non-idempotent ones.)
                if (!client.retryOnConnectionFailure()) {
                    // The application layer has directed us not to retry the request.
                    yield null;
                }

                final var requestBody = userResponse.getRequest().getBody();
                if (requestBody != null && requestBody.isOneShot()) {
                    yield null;
                }
                final var priorResponse = userResponse.getPriorResponse();
                if (priorResponse != null && priorResponse.getStatusCode() == HTTP_CLIENT_TIMEOUT) {
                    // We attempted to retry and got another timeout. Give up.
                    yield null;
                }

                if (retryAfter(userResponse, 0) > 0) {
                    yield null;
                }

                yield userResponse.getRequest();
            }

            case HTTP_UNAVAILABLE -> {
                final var priorResponse = userResponse.getPriorResponse();
                if (priorResponse != null && priorResponse.getStatusCode() == HTTP_UNAVAILABLE) {
                    // We attempted to retry and got another timeout. Give up.
                    yield null;
                }

                if (retryAfter(userResponse, Integer.MAX_VALUE) == 0) {
                    // specifically, received an instruction to retry without delay
                    yield userResponse.getRequest();
                }

                yield null;
            }

            case HTTP_MISDIRECTED_REQUEST -> {
                // Jayo HTTP can coalesce HTTP/2 connections even if the domain names are different. See
                // RealConnection.isEligible(). If we attempted this and the server returned HTTP 421, then we can retry
                // on a different connection.
                final var requestBody = userResponse.getRequest().getBody();
                if (requestBody != null && requestBody.isOneShot()) {
                    yield null;
                }

                if (exchange == null || !exchange.isCoalescedConnection()) {
                    yield null;
                }

                exchange.connection().noCoalescedConnections();
                yield userResponse.getRequest();
            }

            default -> null;
        };
    }

    private @Nullable ClientRequest buildRedirectRequest(final @NonNull ClientResponse userResponse,
                                                         final @NonNull String method) {
        assert userResponse != null;
        assert method != null;

        // Does the client allow redirects?
        if (!client.followRedirects()) {
            return null;
        }

        final var location = userResponse.header("Location");
        if (location == null) {
            return null;
        }

        // Don't follow redirects to unsupported protocols.
        final var url = userResponse.getRequest().getUrl().resolve(location);
        if (url == null) {
            return null;
        }

        // If configured, don't follow redirects between TLS and non-TLS.
        final var sameScheme = url.getScheme().equals(userResponse.getRequest().getUrl().getScheme());
        if (!sameScheme && !client.followTlsRedirects()) {
            return null;
        }

        // Most redirects don't include a request body.
        final var requestBuilder = userResponse.getRequest().newBuilder();
        if (HttpMethodUtils.permitsRequestBody(method)) {
            final var responseCode = userResponse.getStatusCode();
            final var maintainBody = HttpMethodUtils.redirectsWithBody(method) ||
                    responseCode == HTTP_PERM_REDIRECT ||
                    responseCode == HTTP_TEMP_REDIRECT;
            if (HttpMethodUtils.redirectsToGet(method) &&
                    responseCode != HTTP_PERM_REDIRECT &&
                    responseCode != HTTP_TEMP_REDIRECT) {
                requestBuilder.method("GET", null);
            } else {
                final var requestBody = maintainBody ? userResponse.getRequest().getBody() : null;
                requestBuilder.method(method, requestBody);
            }
            if (!maintainBody) {
                requestBuilder.removeHeader("Transfer-Encoding");
                requestBuilder.removeHeader("Content-Length");
                requestBuilder.removeHeader("Content-Type");
            }
        }

        // When redirecting across hosts, drop all authentication headers. This is potentially annoying to the
        // application layer since they have no way to retain them.
        if (!UrlUtils.canReuseConnection(userResponse.getRequest().getUrl(), url)) {
            requestBuilder.removeHeader("Authorization");
        }

        return requestBuilder.url(url).build();
    }

    /**
     * Report and attempt to recover from a failure to communicate with a server. Returns true if {@code je} is
     * recoverable, or false if the failure is permanent. Requests with a body can only be recovered if the body is
     * buffered or if the failure occurred before the request has been sent.
     */
    private boolean recover(final @NonNull JayoException je,
                            final @NonNull RealCall call,
                            final @NonNull ClientRequest userRequest) {
        assert je != null;
        assert call != null;
        assert userRequest != null;

        final var requestSendStarted = !(je instanceof JayoConnectionShutdownException);

        // The application layer has forbidden retries.
        if (!client.retryOnConnectionFailure()) {
            return false;
        }

        // We can't send the request body again.
        if (requestSendStarted && requestIsOneShot(je, userRequest)) {
            return false;
        }

        // This exception is fatal.
        if (!isRecoverable(je, requestSendStarted)) {
            return false;
        }

        // No more routes to attempt.
        if (!call.retryAfterFailure()) {
            return false;
        }

        // For failure recovery, use the same route selector with a new connection.
        return true;
    }

    private static boolean requestIsOneShot(final @NonNull JayoException je, final @NonNull ClientRequest userRequest) {
        assert je != null;
        assert userRequest != null;

        final var requestBody = userRequest.getBody();
        return (requestBody != null && requestBody.isOneShot()) || (je instanceof JayoFileNotFoundException);
    }

    private static boolean isRecoverable(final @NonNull JayoException je, final boolean requestSendStarted) {
        assert je != null;

        // If there was a protocol problem, don't recover.
        if (je instanceof JayoProtocolException) {
            return false;
        }

        // If there was an interruption don't recover, but if there was a timeout connecting to a route, we should try
        // the next route (if there is one).
        if (je instanceof JayoInterruptedIOException) {
            return (je instanceof JayoTimeoutException) && !requestSendStarted;
        }

        // Look for known client-side or negotiation errors that are unlikely to be fixed by trying again with a
        // different route.
        if (je instanceof JayoTlsHandshakeException) {
            // If the problem was a CertificateException from the X509TrustManager, do not retry.
            if (je.getCause().getCause() instanceof CertificateException) {
                return false;
            }
        }

        if (je instanceof JayoTlsPeerUnverifiedException) {
            // e.g. a certificate pinning error.
            return false;
        }

        // An example of one we might want to retry with a different route is a problem connecting to a proxy and would
        // manifest as a standard JayoException. Unless it is one we know we should not retry, we return true and try a
        // new route.
        return true;
    }

    private static int retryAfter(final @NonNull ClientResponse userResponse, final int defaultDelay) {
        final var header = userResponse.header("Retry-After");
        if (header == null) {
            return defaultDelay;
        }

        // https://tools.ietf.org/html/rfc7231#section-7.1.3
        // currently ignores an HTTP date and assumes any non 0 int is a delay
        if (header.matches("\\d+")) {
            return Integer.parseInt(header);
        }
        return Integer.MAX_VALUE;
    }

    private static JayoException withSuppressed(final @NonNull JayoException exception,
                                                final @NonNull List<@NonNull JayoException> suppressed) {
        assert exception != null;
        assert suppressed != null;

        for (final var e : suppressed) {
            exception.addSuppressed(e);
        }
        return exception;
    }
}
