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

import jayo.http.ClientRequest;
import jayo.http.ClientResponse;
import jayo.http.internal.RealHeaders;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;

import static java.net.HttpURLConnection.*;
import static java.time.temporal.ChronoUnit.MILLIS;
import static jayo.http.internal.DateFormatting.toHttpInstantOrNull;
import static jayo.http.internal.Utils.toNonNegativeInt;
import static jayo.http.internal.http.HttpStatusCodes.HTTP_PERM_REDIRECT;
import static jayo.http.internal.http.HttpStatusCodes.HTTP_TEMP_REDIRECT;

/**
 * Given a request and cached response, this figures out whether to use the network, the cache, or both.
 * <p>
 * Selecting a cache strategy may add conditions to the request (like the "If-Modified-Since" header for conditional
 * GETs) or warnings to the cached response (if the cached data is potentially stale).
 */
final class CacheStrategy {
    /**
     * The request to send on the network, or null if this call doesn't use the network.
     */
    final @Nullable ClientRequest networkRequest;
    /**
     * The cached response to return or validate; or null if this call doesn't use a cache.
     */
    final @Nullable ClientResponse cacheResponse;

    CacheStrategy(final @Nullable ClientRequest networkRequest, final @Nullable ClientResponse cacheResponse) {
        this.networkRequest = networkRequest;
        this.cacheResponse = cacheResponse;
    }

    static final class Factory {
        private final @NonNull Instant now;
        final @NonNull ClientRequest request;
        private final @Nullable ClientResponse cacheResponse;

        /**
         * The server's time when the cached response was served, if known.
         */
        private @Nullable Instant served = null;
        private @Nullable String servedString = null;

        /**
         * The last modified date of the cached response, if known.
         */
        private @Nullable Instant lastModified = null;
        private @Nullable String lastModifiedString = null;

        /**
         * The expiration date of the cached response, if known. If both this field and the max age are set, the max age
         * is preferred.
         */
        private @Nullable Instant expires = null;

        /**
         * Extension header set by OkHttp specifying the timestamp when the cached HTTP request was
         * first initiated.
         */
        private @NonNull Instant sentRequest = Instant.MIN;

        /**
         * Extension header set by OkHttp specifying the timestamp when the cached HTTP response was
         * first received.
         */
        private @NonNull Instant receivedResponse = Instant.MIN;

        /**
         * Etag of the cached response.
         */
        private @Nullable String etag = null;

        /**
         * Age of the cached response.
         */
        private int ageSeconds = -1;

        Factory(final @NonNull Instant now,
                final @NonNull ClientRequest request,
                final @Nullable ClientResponse cacheResponse) {
            assert request != null;

            this.now = now;
            this.request = request;
            this.cacheResponse = cacheResponse;

            if (cacheResponse != null) {
                this.sentRequest = cacheResponse.getSentRequestAt();
                this.receivedResponse = cacheResponse.getReceivedResponseAt();
                final var headers = cacheResponse.getHeaders();
                for (var i = 0; i < headers.size(); i++) {
                    final var fieldName = headers.name(i);
                    final var value = headers.value(i);
                    if (fieldName.equalsIgnoreCase("Date")) {
                        served = toHttpInstantOrNull(value);
                        servedString = value;
                    } else if (fieldName.equalsIgnoreCase("Expires")) {
                        expires = toHttpInstantOrNull(value);
                    } else if (fieldName.equalsIgnoreCase("Last-Modified")) {
                        lastModified = toHttpInstantOrNull(value);
                        lastModifiedString = value;
                    } else if (fieldName.equalsIgnoreCase("ETag")) {
                        etag = value;
                    } else if (fieldName.equalsIgnoreCase("Age")) {
                        ageSeconds = toNonNegativeInt(value, -1);
                    }
                }
            }
        }

        /**
         * @return true if computeFreshnessLifetime used a heuristic. If we used a heuristic to serve a cached response
         * older than 24 hours, we are required to attach a warning.
         */
        private boolean isFreshnessLifetimeHeuristic() {
            assert cacheResponse != null;
            return cacheResponse.getCacheControl().maxAgeSeconds() == -1 && expires == null;
        }

        /**
         * @return a strategy to satisfy {@code request} using {@code cacheResponse}.
         */
        @NonNull
        CacheStrategy compute() {
            final var candidate = computeCandidate();

            // We're forbidden from using the network and the cache is insufficient.
            if (candidate.networkRequest != null && request.getCacheControl().onlyIfCached()) {
                return new CacheStrategy(null, null);
            }

            return candidate;
        }

        /**
         * @return a strategy to use assuming the request can use the network.
         */
        private CacheStrategy computeCandidate() {
            // No cached response.
            if (cacheResponse == null) {
                return new CacheStrategy(request, null);
            }

            // Drop the cached response if it's missing a required handshake.
            if (request.isHttps() && cacheResponse.getHandshake() == null) {
                return new CacheStrategy(request, null);
            }

            // If this response shouldn't have been stored, it should never be used as a response source. This check
            // should be redundant as long as the persistence store is well-behaved and the rules are constant.
            if (!isCacheable(cacheResponse, request)) {
                return new CacheStrategy(request, null);
            }

            final var requestCaching = request.getCacheControl();
            if (requestCaching.noCache() || hasConditions(request)) {
                return new CacheStrategy(request, null);
            }

            final var responseCaching = cacheResponse.getCacheControl();

            final var ageMillis = cacheResponseAge();
            var freshMillis = computeFreshnessLifetime();

            if (requestCaching.maxAgeSeconds() != -1) {
                freshMillis = Math.min(freshMillis, Duration.ofSeconds(requestCaching.maxAgeSeconds()).toMillis());
            }

            var minFreshMillis = 0L;
            if (requestCaching.minFreshSeconds() != -1) {
                minFreshMillis = Duration.ofSeconds(requestCaching.minFreshSeconds()).toMillis();
            }

            var maxStaleMillis = 0L;
            if (!responseCaching.mustRevalidate() && requestCaching.maxStaleSeconds() != -1) {
                maxStaleMillis = Duration.ofSeconds(requestCaching.maxStaleSeconds()).toMillis();
            }

            if (!responseCaching.noCache() && ageMillis + minFreshMillis < freshMillis + maxStaleMillis) {
                final var builder = cacheResponse.newBuilder();
                if (ageMillis + minFreshMillis >= freshMillis) {
                    builder.addHeader("Warning", "110 HttpURLConnection \"Response is stale\"");
                }
                final var oneDayMillis = 24 * 60 * 60 * 1000L;
                if (ageMillis > oneDayMillis && isFreshnessLifetimeHeuristic()) {
                    builder.addHeader("Warning", "113 HttpURLConnection \"Heuristic expiration\"");
                }
                return new CacheStrategy(null, builder.build());
            }

            // Find a condition to add to the request. If the condition is satisfied, the response body will not be
            // transmitted.
            final String conditionName;
            final String conditionValue;
            if (etag != null) {
                conditionName = "If-None-Match";
                conditionValue = etag;
            } else if (lastModified != null) {
                conditionName = "If-Modified-Since";
                conditionValue = lastModifiedString;
            } else if (served != null) {
                conditionName = "If-Modified-Since";
                conditionValue = servedString;
            } else {
                return new CacheStrategy(request, null); // No condition! Make a regular request.
            }

            final var conditionalRequestHeaders = (RealHeaders.Builder) request.getHeaders().newBuilder();
            assert conditionValue != null;
            conditionalRequestHeaders.addLenient(conditionName, conditionValue);

            final var conditionalRequest = request.newBuilder()
                    .headers(conditionalRequestHeaders.build())
                    .build();
            return new CacheStrategy(conditionalRequest, cacheResponse);
        }

        /**
         * @return the number of milliseconds that the response was fresh for, starting from the served date.
         */
        private long computeFreshnessLifetime() {
            assert cacheResponse != null;
            final var responseCaching = cacheResponse.getCacheControl();
            if (responseCaching.maxAgeSeconds() != -1) {
                return Duration.ofSeconds(responseCaching.maxAgeSeconds()).toMillis();
            }

            if (expires != null) {
                final var realServed = (served != null) ? served : receivedResponse;
                final var delta = realServed.until(expires, MILLIS);
                return Math.max(delta, 0L);
            }

            if (lastModified != null && cacheResponse.getRequest().getUrl().getQuery() == null) {
                // As recommended by the HTTP RFC and implemented in Firefox, the max age of a document should be
                // defaulted to 10% of the document's age at the time it was served. Default expiration dates aren't
                // used for URIs containing a query.
                final var realServed = (served != null) ? served : sentRequest;
                final var delta = lastModified.until(realServed, MILLIS);
                return (delta > 0L) ? delta / 10 : 0L;
            }

            return 0L;
        }

        /**
         * Returns the current age of the response, in milliseconds. The calculation is specified by
         * RFC 7234, 4.2.3 Calculating Age.
         */
        private long cacheResponseAge() {
            long apparentReceivedAge = (served != null)
                    ? Math.max(0, served.until(receivedResponse, MILLIS))
                    : 0;

            long receivedAge = (ageSeconds != -1)
                    ? Math.max(apparentReceivedAge, Duration.ofSeconds(ageSeconds).toMillis())
                    : apparentReceivedAge;

            long responseDuration = Math.max(0, sentRequest.until(receivedResponse, MILLIS));
            long residentDuration = Math.max(0, receivedResponse.until(now, MILLIS));
            return receivedAge + responseDuration + residentDuration;
        }

        /**
         * @return true if the request contains conditions that save the server from sending a response that the client
         * has locally. When a request is enqueued with its own conditions, the built-in response cache won't be used.
         */
        private boolean hasConditions(final @NonNull ClientRequest request) {
            assert request != null;
            return request.header("If-Modified-Since") != null || request.header("If-None-Match") != null;
        }
    }

    /**
     * @return true if {@code response} can be stored to later serve another request.
     */
    static boolean isCacheable(final @NonNull ClientResponse response, final @NonNull ClientRequest request) {
        assert response != null;
        assert request != null;

        // Always go to network for uncacheable response codes (RFC 7231 section 6.1), This implementation doesn't
        // support caching partial content.
        switch (response.getStatus().code()) {
            case HTTP_OK,
                 HTTP_NOT_AUTHORITATIVE,
                 HTTP_NO_CONTENT,
                 HTTP_MULT_CHOICE,
                 HTTP_MOVED_PERM,
                 HTTP_NOT_FOUND,
                 HTTP_BAD_METHOD,
                 HTTP_GONE,
                 HTTP_REQ_TOO_LONG,
                 HTTP_NOT_IMPLEMENTED,
                 HTTP_PERM_REDIRECT -> {
                // These codes can be cached unless headers forbid it.
            }

            case HTTP_MOVED_TEMP,
                 HTTP_TEMP_REDIRECT -> {
                // These codes can only be cached with the right response headers.
                // https://tools.ietf.org/html/rfc7234#section-3
                // 's-maxage' is not checked because Jayo HTTP is a private cache that should ignore 's-maxage'.
                if (response.header("Expires") == null &&
                        response.getCacheControl().maxAgeSeconds() == -1 &&
                        !response.getCacheControl().isPublic() &&
                        !response.getCacheControl().isPrivate()) {
                    return false;
                }
            }

            default -> {
                // All other codes cannot be cached.
                return false;
            }
        }

        // A 'no-store' directive on request or response prevents the response from being cached.
        return !response.getCacheControl().noStore() && !request.getCacheControl().noStore();
    }
}
