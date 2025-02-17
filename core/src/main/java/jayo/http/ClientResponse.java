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

import jayo.http.internal.RealClientResponse;
import jayo.tls.Protocol;
import jayo.tls.Handshake;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.Closeable;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * An HTTP response. Instances of this class are not immutable: the response body is a one-shot value that may be
 * consumed only once and then closed. All other properties are immutable.
 * <p>
 * This interface extends {@link Closeable}. Closing it simply closes its response body. See {@link ClientResponseBody}
 * for an explanation and examples.
 */
public sealed interface ClientResponse extends Closeable permits RealClientResponse {
    static @NonNull Builder builder() {
        return new RealClientResponse.Builder();
    }

    /**
     * @return the request that initiated this HTTP response. This is not necessarily the same request issued by the
     * application:
     * <ul>
     * <li>It may be transformed by the user's interceptors. For example, an application interceptor may add headers
     * like {@code User-Agent}.
     * <li>It may be the request generated in response to an HTTP redirect or authentication challenge. In this case the
     * request URL may be different from the initial request URL.
     * </ul>
     * Use the {@code getRequest()} of the {@link #getNetworkResponse()} field to get the wire-level request that was
     * transmitted. In the case of follow-ups and redirects, also look at the {@code getRequest()} of the
     * {@link #getPriorResponse()} objects, which have its own {@link #getPriorResponse()}.
     */
    @NonNull
    ClientRequest getRequest();

    /**
     * @return the HTTP protocol, such as {@link Protocol#HTTP_1_1} or {@link Protocol#HTTP_2}.
     */
    @NonNull
    Protocol getProtocol();

    /**
     * @return the HTTP status.
     */
    @NonNull
    ResponseStatus getStatus();

    /**
     * @return the TLS handshake of the connection that carried this response, or null if the response was received
     * without TLS.
     */
    @Nullable
    Handshake getHandshake();

    /**
     * @return the HTTP headers.
     */
    @NonNull
    Headers getHeaders();

    /**
     * @return a readable value if this response was returned from {@link Call#execute()}. Response bodies must be
     * {@linkplain ClientResponseBody closed} and may be consumed only once.
     * <p>
     * This always returns an unreadable {@link ClientResponseBody}, which may implement
     * {@link ClientResponseBody#contentType()} and {@link ClientResponseBody#contentByteSize()}, on responses returned
     * from {@link #getCacheResponse()}, {@link #getNetworkResponse()}, and {@link #getPriorResponse()}.
     */
    @NonNull
    ClientResponseBody getBody();

    /**
     * @return the raw response received from the network. Will be null if this response didn't use the network, such as
     * when the response is fully cached. The body of the returned response should not be read.
     */
    @Nullable
    ClientResponse getNetworkResponse();

    /**
     * @return the raw response received from the cache. Will be null if this response didn't use the cache. For
     * conditional get requests the cache response and network response may both be non-null. The body of the returned
     * response should not be read.
     */
    @Nullable
    ClientResponse getCacheResponse();

    /**
     * @return the response for the HTTP redirect or authorization challenge that triggered this response, or null if
     * this response wasn't triggered by an automatic retry. The body of the returned response should not be read
     * because it has already been consumed by the redirecting client.
     */
    @Nullable
    ClientResponse getPriorResponse();

    /**
     * @return the instant taken immediately before Jayo HTTP transmitted the initiating request over the network. If
     * this response is being served from the cache then this is the instant of the original request.
     */
    @NonNull
    Instant getSentRequestAt();

    /**
     * @return the instant taken immediately after Jayo HTTP received this response's headers from the network. If this
     * response is being served from the cache then this is the instant of the original request.
     */
    @NonNull
    Instant getReceivedResponseAt();

    /**
     * @return true if the code is in [200..300), which means the request was successfully received, understood, and
     * accepted.
     */
    boolean isSuccessful();

    @Nullable
    String header(final @NonNull String name);

    @NonNull
    List<String> headers(final @NonNull String name);

    /**
     * @return the trailers after the HTTP response, which may be empty. It is an error to call this before the entire
     * HTTP response body has been consumed.
     */
    @NonNull
    Headers trailers();

    /**
     * Peeks up to {@code byteCount} bytes from the response body and returns them as a new response body. If fewer than
     * {@code byteCount} bytes are in the response body, the full response body is returned. If more than
     * {@code byteCount} bytes are in the response body, the returned value will be truncated to {@code byteCount}
     * bytes.
     * <p>
     * It is an error to call this method after the body has been consumed.
     * <p>
     * <b>Warning:</b> this method loads the requested bytes into memory. Most applications should set a modest limit on
     * {@code byteCount}, such as 1 MiB.
     */
    @NonNull
    ClientResponseBody peekBody(final long byteCount);

    @NonNull
    Builder newBuilder();

    /**
     * @return true if this response redirects to another resource.
     */
    boolean isRedirect();

    /**
     * @return the RFC 7235 authorization challenges appropriate for this response's code. If the response code is
     * {@code 401 unauthorized}, this returns the "WWW-Authenticate" challenges. If the response code is
     * {@code 407 proxy unauthorized}, this returns the "Proxy-Authenticate" challenges. Otherwise, this returns an
     * empty list of challenges.
     * <p>
     * If a challenge uses the {@code token68} variant instead of auth params, there is exactly one auth param in the
     * challenge at key null. Invalid headers and challenges are ignored. No semantic validation is done, for example
     * that {@code Basic} auth must have a {@code realm} auth param, this is up to the caller that interprets these
     * challenges.
     */
    @NonNull
    List<Challenge> challenges();

    /**
     * @return the cache control directives for this response. This is never null, even if this response contains no
     * {@code Cache-Control} header.
     */
    @NonNull
    CacheControl getCacheControl();

    /**
     * Closes the response body. Equivalent to {@code getBody().close()}.
     */
    @Override
    void close();

    /**
     * The builder used to create a {@link ClientResponse} instance.
     */
    sealed interface Builder permits RealClientResponse.Builder {
        @NonNull
        Builder request(final @NonNull ClientRequest request);

        @NonNull
        Builder protocol(final @NonNull Protocol protocol);

        @NonNull
        Builder code(final int code);

        @NonNull
        Builder message(final @NonNull String message);

        @NonNull
        Builder handshake(final @Nullable Handshake handshake);

        /**
         * Sets the header named {@code name} to {@code value}. If this request already has any headers with that name,
         * they are all replaced.
         */
        @NonNull
        Builder header(final @NonNull String name, final @NonNull String value);

        /**
         * Adds a header with {@code name} to {@code value}. Prefer this method for multiply-valued headers like
         * "Set-Cookie".
         */
        @NonNull
        Builder addHeader(final @NonNull String name, final @NonNull String value);

        /**
         * Removes all headers named {@code name} on this builder.
         */
        @NonNull
        Builder removeHeader(final @NonNull String name);

        /**
         * Remove all headers on this builder and adds {@code headers}.
         */
        @NonNull
        Builder headers(final @NonNull Headers headers);

        @NonNull
        Builder body(final @NonNull ClientResponseBody body);

        @NonNull
        Builder networkResponse(final @Nullable ClientResponse networkResponse);

        @NonNull
        Builder cacheResponse(final @Nullable ClientResponse cacheResponse);

        @NonNull
        Builder priorResponse(final @Nullable ClientResponse priorResponse);

        @NonNull
        Builder sentRequestAt(final @NonNull Instant sentRequestAt);

        @NonNull
        Builder receivedResponseAt(final @NonNull Instant receivedResponseAt);

        @NonNull
        Builder trailers(final @NonNull Supplier<Headers> trailersFn);

        @NonNull
        ClientResponse build();
    }
}
