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

import jayo.http.internal.RealCacheControl;
import org.jspecify.annotations.NonNull;

import java.time.Duration;

/**
 * A Cache-Control header with cache directives from a server or client. These directives set policy on what responses
 * can be stored, and which requests can be satisfied by those stored responses.
 *
 * @see <a href="https://tools.ietf.org/html/rfc7234#section-5.2">RFC 7234, 5.2</a>.
 */
public sealed interface CacheControl permits RealCacheControl {
    /**
     * Cache control request directives that require network validation of responses. Note that such requests may be
     * assisted by the cache via conditional GET requests.
     */
    @NonNull
    CacheControl FORCE_NETWORK =
            builder()
                    .noCache()
                    .build();

    /**
     * Cache control request directives that uses the cache only, even if the cached response is stale. If the response
     * isn't available in the cache or requires server validation, the call will fail with a
     * {@code 504 Unsatisfiable Request}.
     */
    @NonNull
    CacheControl FORCE_CACHE =
            builder()
                    .onlyIfCached()
                    .maxStale(Duration.ofSeconds(Integer.MAX_VALUE))
                    .build();

    static @NonNull Builder builder() {
        return new RealCacheControl.Builder();
    }

    /**
     * @return the cache directives of {@code headers}. This honors both Cache-Control and Pragma headers if they are
     * present.
     */
    static @NonNull CacheControl parse(final @NonNull Headers headers) {
        return RealCacheControl.parse(headers);
    }

    /**
     * In a response, this field's name "no-cache" is misleading. It doesn't prevent us from caching the response; it
     * only means we have to validate the response with the origin server before returning it. We can do this with a
     * conditional GET.
     * <p>
     * In a request, it means do not use a cache to satisfy the request.
     */
    boolean noCache();

    /**
     * If true, this response should not be cached.
     */
    boolean noStore();

    /**
     * The duration past the response's served date that it can be served without validation. Provided in seconds.
     */
    int maxAgeSeconds();

    /**
     * The "s-maxage" directive is the max age for shared caches. Not to be confused with "max-age" for non-shared
     * caches, as in Firefox and Chrome, this directive is not honored by this cache.
     */
    int sMaxAgeSeconds();

    boolean isPrivate();

    boolean isPublic();

    boolean mustRevalidate();

    int maxStaleSeconds();

    int minFreshSeconds();

    /**
     * This field's name "only-if-cached" is misleading. It actually means "do not use the network". It is set by a
     * client who only wants to make a request if it can be fully satisfied by the cache. Cached responses that would
     * require validation (ie. conditional gets) are not permitted if this header is set.
     */
    boolean onlyIfCached();

    boolean noTransform();

    boolean immutable();

    /**
     * The builder used to create a {@link CacheControl} instance.
     */
    sealed interface Builder permits RealCacheControl.Builder {
        /**
         * Don't accept an unvalidated cached response.
         */
        @NonNull
        Builder noCache();

        /**
         * Don't store the server's response in any cache.
         */
        @NonNull
        Builder noStore();

        /**
         * Only accept the response if it is in the cache. If the response isn't cached, a
         * {@code 504 Unsatisfiable Request} response will be returned.
         */
        @NonNull
        Builder onlyIfCached();

        /**
         * Don't accept a transformed response.
         */
        @NonNull
        Builder noTransform();

        @NonNull
        Builder immutable();

        /**
         * Sets the maximum age of a cached response. If the cache response's age exceeds {@code maxAge}, it will not be
         * used and a network request will be made.
         *
         * @param maxAge a non-negative duration. This is stored and transmitted with {@code TimeUnit.SECONDS}
         *               precision; finer precision will be lost.
         */
        @NonNull
        Builder maxAge(final @NonNull Duration maxAge);

        @NonNull
        Builder maxStale(final @NonNull Duration maxStale);

        @NonNull
        Builder minFresh(final @NonNull Duration minFresh);

        @NonNull
        CacheControl build();
    }
}
