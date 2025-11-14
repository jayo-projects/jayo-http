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

import jayo.Jayo;
import jayo.JayoException;
import jayo.JayoProtocolException;
import jayo.Reader;
import jayo.http.ClientResponse;
import jayo.http.Headers;
import jayo.http.Interceptor;
import jayo.http.TrailersSource;
import jayo.http.http2.JayoConnectionShutdownException;
import jayo.http.internal.RealClientResponse;
import jayo.http.tools.HttpMethodUtils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;

import static jayo.http.internal.Utils.stripBody;
import static jayo.http.internal.http.HttpStatusCodes.HTTP_SWITCHING_PROTOCOLS;

/**
 * This is the last interceptor in the chain. It makes a network call to the server.
 */
enum CallServerInterceptor implements Interceptor {
    INSTANCE;

    @Override
    public @NonNull ClientResponse intercept(final @NonNull Chain chain) {
        assert chain != null;
        final var realChain = (RealInterceptorChain) chain;

        final var exchange = realChain.exchange();
        assert exchange != null;
        final var request = realChain.request();
        final var requestBody = request.getBody();
        final var utcClock = Clock.systemUTC();
        final var sentRequestAt = Instant.now(utcClock);

        var invokeStartEvent = true;
        ClientResponse.Builder responseBuilder = null;
        JayoException sendRequestException = null;
        final var hasRequestBody = HttpMethodUtils.permitsRequestBody(request.getMethod()) && requestBody != null;
        final var isUpgradeRequest = "upgrade".equalsIgnoreCase(request.header("Connection"));
        try {
            exchange.writeRequestHeaders(request);

            if (hasRequestBody) {
                // If there's an "Expect: 100-continue" header on the request, wait for an "HTTP/1.1 100 Continue"
                // response before transmitting the request body. If we don't get that, return what we did get (such as
                // a 4xx response) without ever transmitting the request body.
                if ("100-continue".equalsIgnoreCase(request.header("Expect"))) {
                    exchange.flushRequest();
                    responseBuilder = exchange.readResponseHeaders(true);
                    exchange.responseHeadersStart();
                    invokeStartEvent = false;
                }
                if (responseBuilder == null) {
                    if (requestBody.isDuplex()) {
                        // Prepare a duplex body so that the application can send a request body later.
                        exchange.flushRequest();
                        final var bufferedRequestBody = Jayo.buffer(exchange.createRequestBody(request, true));
                        requestBody.writeTo(bufferedRequestBody);
                    } else {
                        // Write the request body if the "Expect: 100-continue" expectation was met.
                        final var bufferedRequestBody = Jayo.buffer(exchange.createRequestBody(request, false));
                        requestBody.writeTo(bufferedRequestBody);
                        bufferedRequestBody.close();
                    }
                } else {
                    exchange.noRequestBody();
                    if (!exchange.connection().isMultiplexed()) {
                        // If the "Expect: 100-continue" expectation wasn't met, prevent the HTTP/1 connection from
                        // being reused. Otherwise, we're still obligated to transmit the request body to leave the
                        // connection in a consistent state.
                        exchange.noNewExchangesOnConnection();
                    }
                }
            } else {
                exchange.noRequestBody();
            }

            if (requestBody == null || !requestBody.isDuplex()) {
                exchange.finishRequest();
            }
        } catch (JayoException e) {
            if (e instanceof JayoConnectionShutdownException) {
                throw e; // No request was sent, so there's no response to read.
            }
            if (!exchange.hasFailure) {
                throw e; // Don't attempt to read the response; we failed to send the request.
            }
            sendRequestException = e;
        }

        try {
            if (responseBuilder == null) {
                responseBuilder = exchange.readResponseHeaders(false);
                if (invokeStartEvent) {
                    exchange.responseHeadersStart();
                    invokeStartEvent = false;
                }
            }
            assert responseBuilder != null;
            var response = responseBuilder
                    .request(request)
                    .handshake(exchange.connection().handshake())
                    .sentRequestAt(sentRequestAt)
                    .receivedResponseAt(Instant.now(utcClock))
                    .build();
            var code = response.getStatusCode();

            while (shouldIgnoreAndWaitForRealResponse(code)) {
                responseBuilder = exchange.readResponseHeaders(false);
                assert responseBuilder != null;
                if (invokeStartEvent) {
                    exchange.responseHeadersStart();
                }
                response = responseBuilder
                        .request(request)
                        .handshake(exchange.connection().handshake())
                        .sentRequestAt(sentRequestAt)
                        .receivedResponseAt(Instant.now(utcClock))
                        .build();
                code = response.getStatusCode();
            }

            exchange.responseHeadersEnd(response);

            final var isUpgradeCode = code == HTTP_SWITCHING_PROTOCOLS;
            if (isUpgradeCode && exchange.connection().isMultiplexed()) {
                throw new JayoProtocolException("Unexpected " + HTTP_SWITCHING_PROTOCOLS +
                        " code on HTTP/2 connection");
            }

            boolean isUpgradeResponse = isUpgradeCode &&
                    "upgrade".equalsIgnoreCase(response.header("Connection"));

            if (isUpgradeRequest && isUpgradeResponse) {
                // This is an HTTP/1 upgrade. (This case includes web socket upgrades.)
                response = ((RealClientResponse.Builder) stripBody(response))
                        .socket(exchange.upgradeToSocket())
                        .build();
            } else {
                // This is not an upgrade response.
                final var responseBody = exchange.openResponseBody(response);
                response = response.newBuilder()
                        .body(responseBody)
                        .trailers(new TrailersSource() {
                            @Override
                            public @Nullable Headers peek() {
                                return exchange.peekTrailers();
                            }

                            @Override
                            public @NonNull Headers get() {
                                final var reader = responseBody.reader();
                                if (reader.isOpen()) {
                                    skipAll(reader);
                                }
                                final var trailers = peek();
                                if (trailers == null) {
                                    throw new IllegalStateException("null trailers after exhausting response body?!");
                                }
                                return trailers;
                            }
                        }).build();
            }

            if ("close".equalsIgnoreCase(response.getRequest().header("Connection")) ||
                    "close".equalsIgnoreCase(response.header("Connection"))) {
                exchange.noNewExchangesOnConnection();
            }
            if ((code == 204 || code == 205) && response.getBody().contentByteSize() > 0L) {
                throw new JayoProtocolException(
                        "HTTP " + code + " had non-zero Content-Length: " + response.getBody().contentByteSize());
            }
            return response;
        } catch (JayoException e) {
            if (sendRequestException != null) {
                sendRequestException.addSuppressed(e);
                throw sendRequestException;
            }
            throw e;
        }
    }

    private static boolean shouldIgnoreAndWaitForRealResponse(final int code) {
        return
                // Server sent a 100-continue even though we did not request one. Try again to read the actual response
                // status.
                code == 100 ||
                        // Handle Processing (102) & Early Hints (103) and any new codes without failing 100 and 101 are
                        // the exceptions with different meanings. But Early Hints not currently exposed
                        (code >= 102 && code < 200);
    }

    private static void skipAll(final @NonNull Reader reader) {
        assert reader != null;

        while (!reader.exhausted()) {
            reader.skip(reader.bytesAvailable());
        }
    }
}
