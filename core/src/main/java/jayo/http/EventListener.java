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

import jayo.JayoException;
import jayo.http.internal.AggregateEventListener;
import jayo.network.Proxy;
import jayo.tls.Handshake;
import jayo.tls.Protocol;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;

/**
 * Listener for metrics events. Extend this class to monitor the quantity, size, and duration of your application's
 * HTTP calls.
 * <p>
 * All start/connect/acquire events will eventually receive a matching end/release event, either successful (non-null
 * parameters), or failed (non-null throwable). The first common parameters of each event pair are used to link the
 * event in case of concurrent or repeated events e.g.
 * {@code dnsStart(call, domainName)} → {@code dnsEnd(call, domainName, inetAddressList)}.
 * <p>
 * Events are typically nested with this structure:
 * <ul>
 * <li>call ({@link #callStart(Call)}, {@link #callEnd(Call)}, {@link #callFailed(Call, JayoException)}
 * <ul>
 * <li>dispatcher queue ({@link #dispatcherQueueStart(Call.AsyncCall, Dispatcher)},
 * {@link #dispatcherQueueEnd(Call.AsyncCall, Dispatcher)} and
 * {@link #dispatcherExecution(Call.AsyncCall, Dispatcher)})</li>
 * <li>proxy selection ({@link #proxySelected(Call, HttpUrl, Proxy)}</li>
 * <li>dns ({@link #dnsStart(Call, String)}, {@link #dnsEnd(Call, String, List)})</li>
 * <li>connect ({@link #connectStart(Call, InetSocketAddress, Proxy)},
 * {@link #connectEnd(Call, InetSocketAddress, Proxy, Protocol)},
 * {@link #connectFailed(Call, InetSocketAddress, Proxy, Protocol, JayoException)})
 * <ul>
 * <li>secure connect ({@link #secureConnectStart(Call)}, {@link #secureConnectEnd(Call, Handshake)})
 * </ul>
 * </li>
 * <li>connection held ({@link #connectionAcquired(Call, Connection)}, {@link #connectionReleased(Call, Connection)})
 * <ul>
 * <li>request ({@link #requestFailed(Call, JayoException)})
 * <ul>
 * <li>headers ({@link #requestHeadersStart(Call)}, {@link #requestHeadersEnd(Call, ClientRequest)})</li>
 * <li>body ({@link #requestBodyStart(Call)}, {@link #requestBodyEnd(Call, long)})</li>
 * </ul>
 * </li>
 * <li>response ({@link #responseFailed(Call, JayoException)})
 * <ul>
 * <li>headers ({@link #responseHeadersStart(Call)}, {@link #responseHeadersEnd(Call, ClientResponse)})</li>
 * <li>body ({@link #responseBodyStart(Call)}, {@link #responseBodyEnd(Call, long)})</li>
 * </li>
 * </ul>
 * </li>
 * </ul>
 * </li>
 * </ul>
 * This nesting is typical but not strict. For example, when calls use "Expect: continue", the request body start and
 * end events occur within the response header events. Similarly, {@linkplain ClientRequestBody#isDuplex() duplex calls}
 * interleave the request and response bodies.
 * <p>
 * Since connections may be reused, the proxy selection, DNS, and connect events may not be present for a call. In
 * future releases of Jayo HTTP these events may also occur concurrently to permit multiple routes to be attempted
 * simultaneously.
 * <p>
 * Events and sequences of events may be repeated for retries and follow-ups.
 * <p>
 * All event methods must execute fast, without external locking, cannot throw exceptions, attempt to mutate the event
 * parameters, or be re-entrant back into the client. Any IO writing to files or network should be done asynchronously.
 */
public interface EventListener {
    /**
     * Invoked as soon as a call is enqueued or executed by a client. In the case of thread or stream limits, this call
     * may be executed well before processing the request is able to begin.
     * <p>
     * This will be invoked only once for a single {@link Call}. Retries of different routes or redirects will be
     * handled within the boundaries of a single {@code callStart} and {@code callEnd}/{@code callFailed} pair.
     */
    default void callStart(final @NonNull Call call) {
    }

    /**
     * Invoked for async calls that were not executed immediately because resources weren't available. The call will
     * remain in the queue until resources are available.
     * <p>
     * Use {@link Dispatcher.Builder#maxRequests(int)} and {@link Dispatcher.Builder#maxRequestsPerHost(int)} to
     * configure how many calls Jayo HTTP performs concurrently.
     */
    default void dispatcherQueueStart(final Call.@NonNull AsyncCall asyncCall, final @NonNull Dispatcher dispatcher) {
    }

    /**
     * Invoked when this async call will be executed.
     * <p>
     * This method is only invoked after {@link #dispatcherQueueStart(Call.AsyncCall, Dispatcher)}.
     */
    default void dispatcherQueueEnd(final Call.@NonNull AsyncCall asyncCall, final @NonNull Dispatcher dispatcher) {
    }

    /**
     * Invoked when this async call starts being executed.
     */
    default void dispatcherExecution(final Call.@NonNull AsyncCall asyncCall, final @NonNull Dispatcher dispatcher) {
    }

    /**
     * Invoked when a proxy is selected. This selected proxy may be null.
     *
     * @param url a URL with only the scheme, hostname, and port specified.
     */
    default void proxySelected(final @NonNull Call call,
                               final @NonNull HttpUrl url,
                               final @Nullable Proxy proxy) {
    }

    /**
     * Invoked just prior to a DNS lookup. See {@link Dns#lookup(String)}.
     * <p>
     * This can be invoked more than 1 time for a single {@link Call}. For example, if the response to the
     * {@link Call#request()} is a redirect to a different host.
     * <p>
     * If the {@link Call} is able to reuse an existing pooled connection, this method will not be invoked.
     *
     * @see ConnectionPool
     */
    default void dnsStart(final @NonNull Call call, final @NonNull String domainName) {
    }

    /**
     * Invoked immediately after a DNS lookup.
     * <p>
     * This method is invoked after {@link #dnsStart(Call, String)}.
     */
    default void dnsEnd(final @NonNull Call call,
                        final @NonNull String domainName,
                        final @NonNull List<InetAddress> inetAddressList) {
    }

    /**
     * Invoked just before initiating a socket connection.
     * <p>
     * This method will be invoked if no existing connection in the {@link ConnectionPool} can be reused.
     * <p>
     * This can be invoked more than 1 time for a single {@link Call}. For example, if the response to the
     * {@link Call#request()} is a redirect to a different address, or a connection is retried.
     */
    default void connectStart(final @NonNull Call call,
                              final @NonNull InetSocketAddress inetSocketAddress,
                              final @Nullable Proxy proxy) {
    }

    /**
     * Invoked just before initiating a TLS connection.
     * <p>
     * This method is invoked if the following conditions are met:
     * <ul>
     * <li>The {@link Call#request()} requires TLS.
     * <li>No existing connection from the {@link ConnectionPool} can be reused.
     * </ul>
     * This can be invoked more than 1 time for a single {@link Call}. For example, if the response to the
     * {@link Call#request()} is a redirect to a different address, or a connection is retried.
     */
    default void secureConnectStart(final @NonNull Call call) {
    }

    /**
     * Invoked immediately after a TLS connection was attempted.
     * <p>
     * This method is invoked after {@link #secureConnectStart(Call)}.
     */
    default void secureConnectEnd(final @NonNull Call call, final @Nullable Handshake handshake) {
    }

    /**
     * Invoked immediately after a socket connection was attempted.
     * <p>
     * If the {@code call} uses HTTPS, this will be invoked after {@link #secureConnectEnd(Call, Handshake)}, otherwise
     * it will be invoked after {@link #connectStart(Call, InetSocketAddress, Proxy)}.
     */
    default void connectEnd(final @NonNull Call call,
                            final @NonNull InetSocketAddress inetSocketAddress,
                            final @Nullable Proxy proxy,
                            final @Nullable Protocol protocol) {
    }

    /**
     * Invoked when a connection attempt fails. This failure is not terminal if further routes are available and failure
     * recovery is enabled.
     * <p>
     * If the {@code call} uses HTTPS, this will be invoked after {@link #secureConnectStart(Call)}, otherwise it will
     * be invoked after {@link #connectStart(Call, InetSocketAddress, Proxy)}.
     */
    default void connectFailed(final @NonNull Call call,
                               final @NonNull InetSocketAddress inetSocketAddress,
                               final @Nullable Proxy proxy,
                               final @Nullable Protocol protocol,
                               final @NonNull JayoException je) {
    }

    /**
     * Invoked after a connection has been acquired for the {@code call}.
     * <p>
     * This can be invoked more than 1 time for a single {@link Call}. For example, if the response to the
     * {@link Call#request()} is a redirect to a different address.
     */
    default void connectionAcquired(final @NonNull Call call, final @NonNull Connection connection) {
    }

    /**
     * Invoked after a connection has been released for the {@code call}.
     * <p>
     * This method is always invoked after {@link #connectionAcquired(Call, Connection)}.
     * <p>
     * This can be invoked more than 1 time for a single {@link Call}. For example, if the response to the
     * {@link Call#request()} is a redirect to a different address.
     */
    default void connectionReleased(final @NonNull Call call, final @NonNull Connection connection) {
    }

    /**
     * Invoked just before sending request headers.
     * <p>
     * The connection is implicit and will generally relate to the last {@link #connectionAcquired(Call, Connection)}
     * event.
     * <p>
     * This can be invoked more than 1 time for a single {@link Call}. For example, if the response to the
     * {@link Call#request()} is a redirect to a different address.
     */
    default void requestHeadersStart(final @NonNull Call call) {
    }

    /**
     * Invoked immediately after sending request headers.
     * <p>
     * This method is always invoked after {@link #requestHeadersStart(Call)}.
     *
     * @param request the request sent over the network. It is an error to access the body of this request.
     */
    default void requestHeadersEnd(final @NonNull Call call, final @NonNull ClientRequest request) {
    }

    /**
     * Invoked just before sending a request body.  Will only be invoked for request allowing and having a request body
     * to send.
     * <p>
     * The connection is implicit and will generally relate to the last {@link #connectionAcquired(Call, Connection)}
     * event.
     * <p>
     * This can be invoked more than 1 time for a single {@link Call}. For example, if the response to the
     * {@link Call#request()} is a redirect to a different address.
     */
    default void requestBodyStart(final @NonNull Call call) {
    }

    /**
     * Invoked immediately after sending a request body.
     * <p>
     * This method is always invoked after {@link #requestBodyStart(Call)}.
     */
    default void requestBodyEnd(final @NonNull Call call, final long byteCount) {
    }

    /**
     * Invoked when a request fails to be written.
     * <p>
     * This method is invoked after {@link #requestHeadersStart(Call)} or {@link #requestBodyStart(Call)}. Note that
     * request failures do not necessarily fail the entire call.
     */
    default void requestFailed(final @NonNull Call call, final @NonNull JayoException je) {
    }

    /**
     * Invoked when response headers are first returned from the server.
     * <p>
     * The connection is implicit and will generally relate to the last {@link #connectionAcquired(Call, Connection)}
     * event.
     * <p>
     * This can be invoked more than 1 time for a single {@link Call}. For example, if the response to the
     * {@link Call#request()} is a redirect to a different address.
     */
    default void responseHeadersStart(final @NonNull Call call) {
    }

    /**
     * Invoked immediately after receiving response headers.
     * <p>
     * This method is always invoked after {@link #responseHeadersStart(Call)}.
     *
     * @param response the response received over the network. It is an error to access the body of this response.
     */
    default void responseHeadersEnd(final @NonNull Call call, final @NonNull ClientResponse response) {
    }

    /**
     * Invoked when data from the response body is first available to the application.
     * <p>
     * This is typically invoked immediately before bytes are returned to the application. If the response body is
     * empty, this is invoked immediately before returning that to the application.
     * <p>
     * If the application closes the response body before attempting a read, this is invoked at the time it is closed.
     * <p>
     * The connection is implicit and will generally relate to the last {@link #connectionAcquired(Call, Connection)}
     * event.
     * <p>
     * This will usually be invoked only 1 time for a single {@link Call}, exceptions are a limited set of cases
     * including failure recovery.
     */
    default void responseBodyStart(final @NonNull Call call) {
    }

    /**
     * Invoked immediately after receiving a response body and completing reading it.
     * <p>
     * Will only be invoked for requests having a response body e.g., won't be invoked for a web socket upgrade.
     * <p>
     * If the response body is closed before the response body is exhausted, this is invoked at the time it is closed.
     * In such calls {@code byteCount} is the number of bytes returned to the application. This may be smaller than the
     * resource's byte count read to completion.
     * <p>
     * This method is always invoked after {@link #responseBodyStart(Call)}.
     */
    default void responseBodyEnd(final @NonNull Call call, final long byteCount) {
    }

    /**
     * Invoked when a response fails to be read.
     * <p>
     * Note that response failures do not necessarily fail the entire call.
     * <p>
     * May be invoked without a prior call to {@link #responseHeadersStart(Call)} or {@link #responseBodyStart(Call)}.
     */
    default void responseFailed(final @NonNull Call call, final @NonNull JayoException je) {
    }

    /**
     * Invoked immediately after a call has completely ended.  This includes delayed consumption of the response body by
     * the caller.
     * <p>
     * This method is always invoked after {@link #callStart(Call)}.
     */
    default void callEnd(final @NonNull Call call) {
    }

    /**
     * Invoked when a call fails permanently.
     * <p>
     * This method is always invoked after {@link #callStart(Call)}.
     */
    default void callFailed(final @NonNull Call call, final @NonNull JayoException je) {
    }

    /**
     * Invoked when a call is canceled.
     * <p>
     * Like all methods in this interface, this is invoked on the thread that triggered the event. But while other
     * events occur sequentially, cancels may occur concurrently with other events. For example, thread A may be
     * executing {@link #responseBodyStart(Call)} while thread B executes {@code canceled(Call)}. Implementations must
     * support such concurrent calls.
     * <p>
     * Note that cancellation is best-effort and that a call may proceed normally after it has been canceled. For
     * example, happy-path events like {@link #requestHeadersStart(Call)} and
     * {@link #requestHeadersEnd(Call, ClientRequest)}  may occur after a call is canceled. Typically, cancellation
     * takes effect when an expensive I/O operation is required.
     * <p>
     * This is invoked at most once, even if {@link Call#cancel()} is invoked multiple times. It may be invoked at any
     * point in a call's life, including before {@link #callStart(Call)} and after {@link #callEnd(Call)}.
     */
    default void canceled(final @NonNull Call call) {
    }

    /**
     * Invoked when a call fails due to cache rules. For example, we're forbidden from using the network and the cache
     * is insufficient.
     */
    default void satisfactionFailure(final @NonNull Call call, final @NonNull ClientResponse response) {
    }

    /**
     * Invoked when a result is served from the cache. The response provided is the top level response, and normal event
     * sequences will not be received.
     * <p>
     * This event will only be received when a cache is configured for the client.
     */
    default void cacheHit(final @NonNull Call call, final @NonNull ClientResponse response) {
    }

    /**
     * Invoked when a response will be served from the network. The response will be available from normal event
     * sequences.
     * <p>
     * This event will only be received when a cache is configured for the client.
     */
    default void cacheMiss(final @NonNull Call call) {
    }

    /**
     * Invoked when a response will be served from the cache or network based on validating the cached response
     * freshness. Will be followed by cacheHit or cacheMiss after the network response is available.
     * <p>
     * This event will only be received when a cache is configured for the client.
     */
    default void cacheConditionalHit(final @NonNull Call call, final @NonNull ClientResponse cachedResponse) {
    }

    /**
     * Invoked when Jayo HTTP decides whether to retry after a connectivity failure.
     * <p>
     * Jayo HTTP won't retry when it is configured not to:
     * <ul>
     * <li>If retries are forbidden with {@link JayoHttpClient#retryOnConnectionFailure()}. (Jayo HTTP's defaults permit
     * retries.)
     * <li>If Jayo HTTP already attempted to transmit the request body, and {@link ClientRequestBody#isOneShot()} is
     * true.
     * </ul>
     * It won't retry if the exception is a bug or a configuration problem, such as:
     * <ul>
     * <li>If the remote peer is untrusted: {@code exception} is a
     * {@linkplain jayo.tls.JayoTlsPeerUnverifiedException JayoTlsPeerUnverifiedException}.
     * <li>If received data is unexpected: {@code exception} is a
     * {@linkplain jayo.JayoProtocolException JayoProtocolException}.
     * </ul>
     * Each call is made on either a reused {@link Connection} from a pool, or on a new connection established from a
     * planned {@link Route}. Jayo HTTP won't retry if it has already attempted all available routes.
     *
     * @param retry {@code true} if Jayo HTTP will make another attempt
     */
    default void retryDecision(final @NonNull Call call,
                               final @NonNull JayoException je,
                               final boolean retry) {
    }

    /**
     * Invoked when Jayo HTTP decides whether to perform a follow-up request.
     * <p>
     * The network response's status code is most influential when deciding how to follow up:
     * <ul>
     * <li>For redirects (301: Moved Permanently, 302: Temporary Redirect, etc.)
     * <li>For auth challenges (401: Unauthorized, 407: Proxy Authentication Required.)
     * <li>For client timeouts (408: Request Time-Out.)
     * <li>For server failures (503: Service Unavailable.)
     * </ul>
     * Response header values like {@code Location} and {@code Retry-After} are also considered.
     * <p>
     * Client configuration may be used to make follow-up decisions, such as:
     * <ul>
     * <li>{@link JayoHttpClient#followRedirects()} must be true to follow redirects.
     * <li>{@link JayoHttpClient#followTlsRedirects()} must be true to follow redirects that add or remove HTTPS.
     * <li>{@link JayoHttpClient#getAuthenticator()} must respond to an authorization challenge.
     * </ul>
     *
     * @param networkResponse the intermediate response that may require a follow-up request.
     * @param nextRequest     the follow-up request that will be made. Null if no follow-up will be made.
     */
    default void followUpDecision(final @NonNull Call call,
                                  final @NonNull ClientResponse networkResponse,
                                  final @Nullable ClientRequest nextRequest) {
    }

    /**
     * @return a new {@link EventListener} that publishes events to {@code leftEventListener} and then
     * {@code rightEventListener}.
     */
    static @NonNull EventListener plus(final @NonNull EventListener leftEventListener,
                                       final @NonNull EventListener rightEventListener) {
        Objects.requireNonNull(leftEventListener);
        Objects.requireNonNull(rightEventListener);

        return AggregateEventListener.create(leftEventListener, rightEventListener);
    }

    @FunctionalInterface
    interface Factory {
        /**
         * Creates an instance of the {@link EventListener} for a particular {@link Call}. The returned
         * {@link EventListener} instance will be used during the lifecycle of {@code call}.
         * <p>
         * This method is invoked after {@code call} is created. See
         * {@link JayoHttpClient#newCall(ClientRequest, Tag[])}.
         * <p>
         * <b>It is an error for implementations to issue any mutating operations on the {@code call} instance from this
         * method.</b>
         */
        @NonNull
        EventListener create(final @NonNull Call call);
    }

    public static final @NonNull EventListener NONE = new EventListener() {
    };
}
