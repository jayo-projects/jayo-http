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

package jayo.http.internal.cache;

import jayo.Buffer;
import jayo.Jayo;
import jayo.JayoException;
import jayo.RawReader;
import jayo.http.*;
import jayo.http.internal.RealHeaders;
import jayo.http.internal.connection.RealCall;
import jayo.http.tools.HttpMethodUtils;
import jayo.tls.Protocol;
import jayo.tools.JayoUtils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Objects;

import static java.net.HttpURLConnection.HTTP_GATEWAY_TIMEOUT;
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static jayo.http.internal.Utils.*;
import static jayo.http.internal.http.ExchangeCodec.DISCARD_STREAM_TIMEOUT;
import static jayo.http.internal.http.HttpHeaders.promisesBody;

/**
 * Serves requests from the cache and writes responses to the cache.
 */
public final class CacheInterceptor implements Interceptor {
    private final @Nullable RealCache cache;

    public CacheInterceptor(final @Nullable Cache cache) {
        this.cache = (RealCache) cache;
    }

    @Override
    public @NonNull ClientResponse intercept(final @NonNull Chain chain) {
        assert chain != null;

        final var call = chain.call();
        final var cacheCandidate = cache != null ? cache.get(requestForCache(chain.request())) : null;

        final var now = Instant.now();

        final var strategy = new CacheStrategy.Factory(now, chain.request(), cacheCandidate).compute();
        final var networkRequest = strategy.networkRequest;
        final var cacheResponse = strategy.cacheResponse;

        if (cache != null) {
            cache.trackResponse(strategy);
        }
        final var listener = (call instanceof RealCall realCall) ? realCall.eventListener() : EventListener.NONE;

        if (cacheCandidate != null && cacheResponse == null) {
            // The cache candidate wasn't applicable. Close it.
            closeQuietly(cacheCandidate.getBody());
        }

        // If we're forbidden from using the network, and the cache is not enough, fail.
        if (networkRequest == null && cacheResponse == null) {
            final var response = ClientResponse.builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(HTTP_GATEWAY_TIMEOUT)
                    .message("Unsatisfiable Request (only-if-cached)")
                    .sentRequestAt(Instant.MIN)
                    .receivedResponseAt(Instant.now())
                    .build();
            listener.satisfactionFailure(call, response);
            return response;
        }

        // If we don't need the network, we're done.
        if (networkRequest == null) {
            final var response = cacheResponse
                    .newBuilder()
                    .cacheResponse(stripBody(cacheResponse).build())
                    .build();
            listener.cacheHit(call, response);
            return response;
        }

        if (cacheResponse != null) {
            listener.cacheConditionalHit(call, cacheResponse);
        } else if (cache != null) {
            listener.cacheMiss(call);
        }

        ClientResponse networkResponse = null;
        try {
            networkResponse = chain.proceed(networkRequest);
        } finally {
            // If we're crashing on I/O or otherwise, don't leak the cache body. Close it.
            if (networkResponse == null && cacheCandidate != null) {
                closeQuietly(cacheCandidate.getBody());
            }
        }

        // If we have a cache response too, then we're doing a conditional get.
        if (cacheResponse != null) {
            if (networkResponse.getStatus().code() == HTTP_NOT_MODIFIED) {
                final var response = cacheResponse
                        .newBuilder()
                        .headers(combine(cacheResponse.getHeaders(), networkResponse.getHeaders()))
                        .sentRequestAt(networkResponse.getSentRequestAt())
                        .receivedResponseAt(networkResponse.getReceivedResponseAt())
                        .cacheResponse(stripBody(cacheResponse).build())
                        .networkResponse(stripBody(networkResponse).build())
                        .build();

                networkResponse.getBody().close();

                // Update the cache after combining headers but before stripping the Content-Encoding header
                // (as performed by initContentStream()).
                assert cache != null;
                cache.trackConditionalCacheHit();
                cache.update(cacheResponse, response);
                listener.cacheHit(call, response);
                return response;
            } else {
                closeQuietly(cacheResponse.getBody());
            }
        }

        final var response = networkResponse.newBuilder()
                .cacheResponse(cacheResponse != null ? stripBody(cacheResponse).build() : null)
                .networkResponse(stripBody(networkResponse).build())
                .build();

        if (cache != null) {
            final var cacheNetworkRequest = requestForCache(networkRequest);

            if (promisesBody(response) && CacheStrategy.isCacheable(response, cacheNetworkRequest)) {
                // Offer this request to the cache.
                final var cacheRequest = cache.put(response.newBuilder().request(cacheNetworkRequest).build());
                final var cacheWritingResponse = cacheWritingResponse(cacheRequest, response);
                if (cacheResponse != null) {
                    // This will log a conditional cache miss only.
                    listener.cacheMiss(call);
                }
                return cacheWritingResponse;
            }

            if (HttpMethodUtils.invalidatesCache(networkRequest.getMethod())) {
                try {
                    cache.remove(networkRequest);
                } catch (JayoException ignored) {
                    // The cache cannot be written.
                }
            }
        }

        return response;
    }

    /**
     * @return a new reader that writes bytes to {@code cacheRequest} as they are read by the source consumer. This is
     * careful to discard bytes left over when the stream is closed; otherwise we may never exhaust the source stream
     * and therefore not complete the cached response.
     */
    private @NonNull ClientResponse cacheWritingResponse(final @Nullable CacheRequest cacheRequest,
                                                         final @NonNull ClientResponse response) {
        // Some apps return a null body; for compatibility we treat that like a null cache request.
        if (cacheRequest == null) {
            return response;
        }
        final var cacheBodyUnbuffered = cacheRequest.body();

        final var reader = response.getBody().reader();
        final var cacheBody = Jayo.buffer(cacheBodyUnbuffered);

        final var cacheWritingReader = new RawReader() {
            private boolean cacheRequestClosed = false;

            @Override
            public long readAtMostTo(final @NonNull Buffer destination, final long byteCount) {
                Objects.requireNonNull(destination);

                final long bytesRead;
                try {
                    bytesRead = reader.readAtMostTo(destination, byteCount);
                } catch (JayoException je) {
                    if (!cacheRequestClosed) {
                        cacheRequestClosed = true;
                        cacheRequest.abort(); // Failed to write a complete cache response.
                    }
                    throw je;
                }

                if (bytesRead == -1L) {
                    if (!cacheRequestClosed) {
                        cacheRequestClosed = true;
                        cacheBody.close(); // The cache response is complete!
                    }
                    return -1L;
                }

                destination.copyTo(
                        JayoUtils.buffer(cacheBody),
                        destination.bytesAvailable() - bytesRead,
                        bytesRead);
                cacheBody.emitCompleteSegments();
                return bytesRead;
            }

            @Override
            public void close() {
                if (!cacheRequestClosed && !discard(this, DISCARD_STREAM_TIMEOUT)) {
                    cacheRequestClosed = true;
                    cacheRequest.abort();
                }
                reader.close();
            }
        };

        final var contentType = response.header("Content-Type");
        final var contentLength = response.getBody().contentByteSize();
        MediaType mediaType = null;
        if (contentType != null) {
            mediaType = MediaType.parse(contentType);
        }

        final ClientResponseBody responseBody;
        if (mediaType != null) {
            responseBody = ClientResponseBody.create(Jayo.buffer(cacheWritingReader), mediaType, contentLength);
        } else {
            responseBody = ClientResponseBody.create(Jayo.buffer(cacheWritingReader), contentLength);
        }
        return response.newBuilder()
                .body(responseBody)
                .build();
    }

    private static ClientRequest requestForCache(final @NonNull ClientRequest request) {
        assert request != null;

        final var cacheUrlOverride = request.getCacheUrlOverride();
        if (cacheUrlOverride != null && (request.getMethod().equals("GET") || request.getMethod().equals("POST"))) {
            return request.newBuilder()
                    .url(cacheUrlOverride)
                    .cacheUrlOverride(null)
                    .method("GET", null);
        }
        return request; // else return the initial request as-is
    }

    /**
     * Combines cached headers with a network headers as defined by RFC 7234, 4.3.4.
     */
    private static @NonNull Headers combine(final @NonNull Headers cachedHeaders,
                                            final @NonNull Headers networkHeaders) {
        assert cachedHeaders != null;
        assert networkHeaders != null;

        final var result = new RealHeaders.Builder();

        for (int index = 0; index < cachedHeaders.size(); index++) {
            String fieldName = cachedHeaders.name(index);
            String value = cachedHeaders.value(index);
            if ("Warning".equalsIgnoreCase(fieldName) && value.startsWith("1")) {
                // Drop 100-level freshness warnings.
                continue;
            }
            if (isContentSpecificHeader(fieldName) ||
                    !isEndToEnd(fieldName) ||
                    networkHeaders.get(fieldName) == null) {
                result.addLenient(fieldName, value);
            }
        }

        for (int index = 0; index < networkHeaders.size(); index++) {
            String fieldName = networkHeaders.name(index);
            if (!isContentSpecificHeader(fieldName) && isEndToEnd(fieldName)) {
                result.addLenient(fieldName, networkHeaders.value(index));
            }
        }

        return result.build();
    }

    /**
     * @return true if {@code fieldName} is content specific and therefore should always be used from cached headers.
     */
    private static boolean isContentSpecificHeader(final @NonNull String fieldName) {
        assert fieldName != null;

        return "Content-Length".equalsIgnoreCase(fieldName) ||
                "Content-Encoding".equalsIgnoreCase(fieldName) ||
                "Content-Type".equalsIgnoreCase(fieldName);
    }

    /**
     * @return true if {@code fieldName} is an end-to-end HTTP header, as defined by RFC 2616, 13.5.1.
     */
    private static boolean isEndToEnd(String fieldName) {
        return !"Connection".equalsIgnoreCase(fieldName) &&
                !"Keep-Alive".equalsIgnoreCase(fieldName) &&
                !"Proxy-Authenticate".equalsIgnoreCase(fieldName) &&
                !"Proxy-Authorization".equalsIgnoreCase(fieldName) &&
                !"TE".equalsIgnoreCase(fieldName) &&
                !"Trailers".equalsIgnoreCase(fieldName) &&
                !"Transfer-Encoding".equalsIgnoreCase(fieldName) &&
                !"Upgrade".equalsIgnoreCase(fieldName);
    }
}
