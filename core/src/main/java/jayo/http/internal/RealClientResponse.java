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

import jayo.Buffer;
import jayo.http.*;
import jayo.http.internal.connection.Exchange;
import jayo.http.internal.http.HttpHeaders;
import jayo.tls.Handshake;
import jayo.tls.Protocol;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import static java.net.HttpURLConnection.*;
import static jayo.http.internal.http.HttpStatusCodes.HTTP_PERM_REDIRECT;
import static jayo.http.internal.http.HttpStatusCodes.HTTP_TEMP_REDIRECT;

public final class RealClientResponse implements ClientResponse {
    private final @NonNull ClientRequest request;
    private final @NonNull Protocol protocol;
    private final @NonNull ResponseStatus status;
    private final @Nullable Handshake handshake;
    private final @NonNull Headers headers;
    private final @NonNull ClientResponseBody body;
    private final @Nullable ClientResponse networkResponse;
    private final @Nullable ClientResponse cacheResponse;
    private final @Nullable ClientResponse priorResponse;
    private final @NonNull Instant sentRequestAt;
    private final @NonNull Instant receivedResponseAt;
    private final @Nullable Exchange exchange;
    private final @NonNull TrailersSource trailersSource;

    private @Nullable CacheControl lazyCacheControl = null;

    public RealClientResponse(final @NonNull ClientRequest request,
                              final @NonNull Protocol protocol,
                              final @NonNull ResponseStatus status,
                              final @Nullable Handshake handshake,
                              final @NonNull Headers headers,
                              final @NonNull ClientResponseBody body,
                              final @Nullable ClientResponse networkResponse,
                              final @Nullable ClientResponse cacheResponse,
                              final @Nullable ClientResponse priorResponse,
                              final @NonNull Instant sentRequestAt,
                              final @NonNull Instant receivedResponseAt,
                              final @Nullable Exchange exchange,
                              final @NonNull TrailersSource trailersSource) {
        assert request != null;
        assert protocol != null;
        assert status != null;
        assert headers != null;
        assert body != null;
        assert sentRequestAt != null;
        assert receivedResponseAt != null;
        assert trailersSource != null;

        this.request = request;
        this.protocol = protocol;
        this.status = status;
        this.handshake = handshake;
        this.headers = headers;
        this.body = body;
        this.networkResponse = networkResponse;
        this.cacheResponse = cacheResponse;
        this.priorResponse = priorResponse;
        this.sentRequestAt = sentRequestAt;
        this.receivedResponseAt = receivedResponseAt;
        this.exchange = exchange;
        this.trailersSource = trailersSource;
    }

    @Override
    public @NonNull ClientRequest getRequest() {
        return request;
    }

    @Override
    public @NonNull Protocol getProtocol() {
        return protocol;
    }

    @Override
    public @NonNull ResponseStatus getStatus() {
        return status;
    }

    @Override
    public @Nullable Handshake getHandshake() {
        return handshake;
    }

    @Override
    public @NonNull Headers getHeaders() {
        return headers;
    }

    @Override
    public @NonNull ClientResponseBody getBody() {
        return body;
    }

    @Override
    public @Nullable ClientResponse getNetworkResponse() {
        return networkResponse;
    }

    @Override
    public @Nullable ClientResponse getCacheResponse() {
        return cacheResponse;
    }

    @Override
    public @Nullable ClientResponse getPriorResponse() {
        return priorResponse;
    }

    @Override
    public @NonNull Instant getSentRequestAt() {
        return sentRequestAt;
    }

    @Override
    public @NonNull Instant getReceivedResponseAt() {
        return receivedResponseAt;
    }

    @Override
    public boolean isSuccessful() {
        return status.code() > 199 && status.code() < 300;
    }

    @Override
    public @Nullable String header(final @NonNull String name) {
        return headers.get(name);
    }

    @Override
    public @NonNull List<String> headers(final @NonNull String name) {
        return headers.values(name);
    }

    @Override
    public @NonNull Headers trailers() {
        return trailersSource.get();
    }

    @Override
    public @Nullable Headers peekTrailers() {
        return trailersSource.peek();
    }

    @Override
    public @NonNull ClientResponseBody peekBody(final long byteCount) {
        final var peeked = body.reader().peek();
        final var buffer = Buffer.create();
        peeked.request(byteCount);
        buffer.writeFrom(peeked, Math.min(byteCount, peeked.bytesAvailable()));
        final var contentType = body.contentType();
        return (contentType != null)
                ? ClientResponseBody.create(buffer, contentType, buffer.bytesAvailable())
                : ClientResponseBody.create(buffer, buffer.bytesAvailable());
    }

    @Override
    public @NonNull Builder newBuilder() {
        return new Builder(this);
    }

    @Override
    public boolean isRedirect() {
        return switch (status.code()) {
            case HTTP_PERM_REDIRECT, HTTP_TEMP_REDIRECT, HTTP_MULT_CHOICE,
                 HTTP_MOVED_PERM, HTTP_MOVED_TEMP, HTTP_SEE_OTHER -> true;
            default -> false;
        };
    }

    @Override
    public @NonNull List<Challenge> challenges() {
        final String challengeHeaderName;
        switch (status.code()) {
            case HTTP_UNAUTHORIZED -> challengeHeaderName = "WWW-Authenticate";
            case HTTP_PROXY_AUTH -> challengeHeaderName = "Proxy-Authenticate";
            default -> {
                return List.of();
            }
        }
        return HttpHeaders.parseChallenges(headers, challengeHeaderName);
    }

    @Override
    public @NonNull CacheControl getCacheControl() {
        if (lazyCacheControl == null) {
            lazyCacheControl = CacheControl.parse(getHeaders());
        }
        return lazyCacheControl;
    }

    @Override
    public void close() {
        getBody().close();
    }

    @Override
    public @NonNull String toString() {
        return "ClientResponse{" +
                "protocol=" + getProtocol() +
                ", status=" + getStatus() +
                ", url=" + getRequest().getUrl() +
                "}";
    }

    public static final class Builder implements ClientResponse.Builder {
        private @Nullable ClientRequest request = null;
        private @Nullable Protocol protocol = null;
        private int code = -1;
        private @Nullable String message = null;
        private @Nullable Handshake handshake = null;
        private Headers.@NonNull Builder headers;
        private @NonNull ClientResponseBody body = ClientResponseBody.EMPTY;
        private @Nullable ClientResponse networkResponse = null;
        private @Nullable ClientResponse cacheResponse = null;
        private @Nullable ClientResponse priorResponse = null;
        private @Nullable Instant sentRequestAt = null;
        private @Nullable Instant receivedResponseAt = null;
        private @Nullable Exchange exchange = null;
        private @NonNull TrailersSource trailersSource = TrailersSource.EMPTY;

        public Builder() {
            headers = Headers.builder();
        }

        private Builder(final @NonNull RealClientResponse response) {
            this.request = response.getRequest();
            this.protocol = response.getProtocol();
            this.code = response.getStatus().code();
            this.message = response.getStatus().message();
            this.handshake = response.getHandshake();
            this.headers = response.getHeaders().newBuilder();
            this.body = response.getBody();
            this.networkResponse = response.getNetworkResponse();
            this.cacheResponse = response.getCacheResponse();
            this.priorResponse = response.getPriorResponse();
            this.sentRequestAt = response.getSentRequestAt();
            this.receivedResponseAt = response.getReceivedResponseAt();
            this.exchange = response.exchange;
            this.trailersSource = response.trailersSource;
        }

        @Override
        public @NonNull Builder request(final @NonNull ClientRequest request) {
            this.request = Objects.requireNonNull(request);
            return this;
        }

        @Override
        public @NonNull Builder protocol(final @NonNull Protocol protocol) {
            this.protocol = Objects.requireNonNull(protocol);
            return this;
        }

        @Override
        public @NonNull Builder code(final int code) {
            this.code = code;
            return this;
        }

        @Override
        public @NonNull Builder message(final @NonNull String message) {
            this.message = Objects.requireNonNull(message);
            return this;
        }

        @Override
        public @NonNull Builder handshake(final @Nullable Handshake handshake) {
            this.handshake = handshake;
            return this;
        }

        @Override
        public @NonNull Builder header(final @NonNull String name, final @NonNull String value) {
            headers.set(name, value);
            return this;
        }

        @Override
        public @NonNull Builder addHeader(final @NonNull String name, final @NonNull String value) {
            headers.add(name, value);
            return this;
        }

        @Override
        public @NonNull Builder removeHeader(final @NonNull String name) {
            headers.removeAll(name);
            return this;
        }

        @Override
        public @NonNull Builder headers(final @NonNull Headers headers) {
            Objects.requireNonNull(headers);
            this.headers = headers.newBuilder();
            return this;
        }

        @Override
        public @NonNull Builder body(final @NonNull ClientResponseBody body) {
            this.body = Objects.requireNonNull(body);
            return this;
        }

        @Override
        public @NonNull Builder networkResponse(final @Nullable ClientResponse networkResponse) {
            checkSupportResponse("networkResponse", networkResponse);
            this.networkResponse = networkResponse;
            return this;
        }

        @Override
        public @NonNull Builder cacheResponse(final @Nullable ClientResponse cacheResponse) {
            checkSupportResponse("cacheResponse", cacheResponse);
            this.cacheResponse = cacheResponse;
            return this;
        }

        @Override
        public @NonNull Builder priorResponse(final @Nullable ClientResponse priorResponse) {
            this.priorResponse = priorResponse;
            return this;
        }

        @Override
        public @NonNull Builder sentRequestAt(final @NonNull Instant sentRequestAt) {
            this.sentRequestAt = Objects.requireNonNull(sentRequestAt);
            return this;
        }

        @Override
        public @NonNull Builder receivedResponseAt(final @NonNull Instant receivedResponseAt) {
            this.receivedResponseAt = Objects.requireNonNull(receivedResponseAt);
            return this;
        }

        @Override
        public @NonNull Builder trailers(final @NonNull TrailersSource trailersSource) {
            this.trailersSource = Objects.requireNonNull(trailersSource);
            return this;
        }

        public void initExchange(final @NonNull Exchange exchange) {
            assert exchange != null;

            this.exchange = exchange;
        }

        @Override
        public @NonNull ClientResponse build() {
            if (code < 0) {
                throw new IllegalStateException("code < 0: " + code);
            }
            Objects.requireNonNull(request, "request == null");
            Objects.requireNonNull(protocol, "protocol == null");
            Objects.requireNonNull(message, "message == null");

            return new RealClientResponse(
                    request,
                    protocol,
                    new ResponseStatus(code, message),
                    handshake,
                    headers.build(),
                    body,
                    networkResponse,
                    cacheResponse,
                    priorResponse,
                    (sentRequestAt != null) ? sentRequestAt : Instant.EPOCH,
                    (receivedResponseAt != null) ? receivedResponseAt : Instant.EPOCH,
                    exchange,
                    trailersSource
            );
        }

        public int code() {
            return code;
        }

        private static void checkSupportResponse(final @NonNull String name, final @Nullable ClientResponse response) {
            assert name != null;

            if (response == null) {
                return;
            }

            if (response.getNetworkResponse() != null) {
                throw new IllegalArgumentException(name + ".getNetworkResponse() != null");
            }
            if (response.getCacheResponse() != null) {
                throw new IllegalArgumentException(name + ".getCacheResponse() != null");
            }
            if (response.getPriorResponse() != null) {
                throw new IllegalArgumentException(name + ".getPriorResponse() != null");
            }
        }
    }
}
