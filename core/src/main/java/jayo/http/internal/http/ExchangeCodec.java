/*
 * Copyright (c) 2025-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 *
 * Forked from OkHttp (https://github.com/square/okhttp), original copyright is below
 *
 * Copyright (C) 2012 The Android Open Source Project
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

package jayo.http.internal.http;

import jayo.JayoException;
import jayo.RawReader;
import jayo.RawSocket;
import jayo.RawWriter;
import jayo.http.ClientRequest;
import jayo.http.ClientResponse;
import jayo.http.Headers;
import jayo.http.Route;
import jayo.http.internal.connection.RealCall;
import jayo.http.internal.http1.Http1ExchangeCodec;
import jayo.http.internal.http2.Http2ExchangeCodec;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Duration;

/**
 * Encodes HTTP requests and decodes HTTP responses.
 */
public sealed interface ExchangeCodec permits Http1ExchangeCodec, Http2ExchangeCodec {
    /**
     * The timeout to use while discarding a stream of input data. Since this is used for connection reuse, this timeout
     * should be significantly less than the time it takes to establish a new connection.
     */
    @NonNull
    Duration DISCARD_STREAM_TIMEOUT = Duration.ofMillis(100);

    /**
     * The connection or CONNECT tunnel that owns this codec.
     */
    @NonNull
    Carrier getCarrier();

    /**
     * @return true if the response body and (possibly empty) trailers have been received.
     */
    boolean isResponseComplete();

    /**
     * @return the socket that carries this exchange.
     */
    @NonNull
    RawSocket getSocket();

    /**
     * @return a RawWriter where the request body can be streamed.
     */
    @NonNull
    RawWriter createRequestBody(final @NonNull ClientRequest request, final long contentLength);

    /**
     * This should update the HTTP engine's sentRequestMillis field.
     */
    void writeRequestHeaders(final @NonNull ClientRequest request);

    /**
     * Flush the request to the underlying socket.
     */
    void flushRequest();

    /**
     * Flush the request to the underlying socket and signal no more bytes will be transmitted.
     */
    void finishRequest();

    /**
     * Parses bytes of a response header from an HTTP transport.
     *
     * @param expectContinue true to return null if this is an intermediate response with a "100"
     *                       response code. Otherwise, this method never returns null.
     */
    ClientResponse.@Nullable Builder readResponseHeaders(final boolean expectContinue);

    long reportedContentByteSize(final @NonNull ClientResponse response);

    @NonNull
    RawReader openResponseBodyReader(final @NonNull ClientResponse response);

    /**
     * @return the trailers after the HTTP response if they're ready. It may be empty.
     */
    @Nullable
    Headers peekTrailers();

    /**
     * Cancel this stream. Resources held by this stream will be cleaned up, though not synchronously. That may happen
     * later by the connection pool thread.
     */
    void cancel();

    /**
     * Carries an exchange. This is usually a connection, but it could also be a connect plan for CONNECT tunnels. Note
     * that CONNECT tunnels are significantly less capable than connections.
     */
    sealed interface Carrier
            permits jayo.http.internal.connection.ConnectPlan, jayo.http.internal.connection.RealConnection {
        @NonNull
        Route route();

        void trackFailure(final @NonNull RealCall call, final @Nullable JayoException e);

        /**
         * Prevent further exchanges from being created on this connection.
         */
        void noNewExchanges();

        void cancel();
    }
}
