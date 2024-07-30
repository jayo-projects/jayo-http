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

import jayo.http.internal.RealClientRequest;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.net.URL;
import java.util.List;

/**
 * A HTTP client request. Instances of this class are immutable if their {@linkplain #getBody() body} is null or itself
 * immutable.
 */
public sealed interface ClientRequest permits RealClientRequest {
    static @NonNull Builder builder() {
        return new RealClientRequest.Builder();
    }

    @NonNull
    HttpUrl getUrl();

    @NonNull
    String getMethod();

    @NonNull
    Headers getHeaders();

    @Nullable
    ClientRequestBody getBody();

    @Nullable
    HttpUrl getCacheUrlOverride();

    boolean isHttps();

    @Nullable
    String header(final @NonNull String name);

    @NonNull
    List<String> headers(final @NonNull String name);

    /**
     * @return the cache control directives for this response. This is never null, even if this response contains no
     * {@code Cache-Control} header.
     */
    @NonNull
    CacheControl getCacheControl();

    /**
     * @return the tag attached with {@code type} as a key, or null if no tag is attached with that key.
     */
    <T> @Nullable T tag(final @NonNull Class<T> type);

    /**
     * @return the tag attached with {@code Object.class} as a key, or null if no tag is attached with that key.
     */
    @Nullable Object tag();

    /**
     * The builder used to create a {@link ClientRequest} instance.
     */
    sealed interface Builder permits RealClientRequest.Builder {
        /**
         * Sets the URL target of this request.
         */
        @NonNull
        Builder url(final @NonNull HttpUrl url);

        /**
         * Sets the URL target of this request.
         *
         * @throws IllegalArgumentException if {@code url} is not a valid HTTP or HTTPS URL. Avoid this exception by
         *                                  calling {@link HttpUrl#parse(String)}; it returns null for invalid URLs.
         */
        @NonNull
        Builder url(final @NonNull String url);

        /**
         * Sets the URL target of this request.
         *
         * @throws IllegalArgumentException if the scheme of {@code url} is not `http` or `https`.
         */
        @NonNull
        Builder url(final @NonNull URL url);

        /**
         * Sets the header with the specified name and value. If this request already has any headers with that name,
         * they are all replaced.
         */
        @NonNull
        Builder header(final @NonNull String name, final @NonNull String value);

        /**
         * Adds a header with the specified name and value. Prefer this method for multiple-valued headers like "Cookie"
         * <p>
         * Note that for some headers including `Content-Length` and `Content-Encoding`, Jayo HTTP may replace
         * {@code value} with a header derived from the request body.
         */
        @NonNull
        Builder addHeader(final @NonNull String name, final @NonNull String value);

        /**
         * Remove all values of a given header name.
         */
        @NonNull
        Builder removeHeader(final @NonNull String name);

        /**
         * Remove all headers on this builder and adds headers.
         */
        @NonNull
        Builder headers(final @NonNull Headers headers);

        /**
         * Sets this request's `Cache-Control` header, replacing any cache control headers already present. If
         * {@code cacheControl} doesn't define any directives, this clears this request's cache-control headers.
         */
        @NonNull
        Builder cacheControl(final @NonNull CacheControl cacheControl);

        /**
         * Attaches {@code tag} to the request using {@code type} as a key. Tags can be read from a request using
         * {@link ClientRequest#tag(Class)}. Use null to remove any existing tag assigned for {@code type}.
         * <p>
         * Use this API to attach timing, debugging, or other application data to a request so that you may read it in
         * interceptors, event listeners, or callbacks.
         */
        @NonNull
        <T> Builder tag(final @NonNull Class<T> type, final @Nullable T tag);

        /**
         * Attaches {@code tag} to the request using `Object.class` as a key.
         */
        @NonNull
        Builder tag(final @Nullable Object tag);

        /**
         * Override the {@link ClientRequest#getUrl()} for caching, if it is either polluted with transient query
         * params, or has a canonical URL possibly for a CDN.
         * <p>
         * Note that POST requests will not be sent to the server if this URL is set and matches a cached response.
         */
        @NonNull
        Builder cacheUrlOverride(final @Nullable HttpUrl cacheUrlOverride);

        /**
         * Build a {@link ClientRequest} for a GET HTTP call
         */
        @NonNull
        ClientRequest get();

        /**
         * Build a {@link ClientRequest} for a HEAD HTTP call
         */
        @NonNull
        ClientRequest head();

        /**
         * Build a {@link ClientRequest} for a POST HTTP call
         */
        @NonNull
        ClientRequest post(final @NonNull ClientRequestBody requestBody);

        /**
         * Build a {@link ClientRequest} for a DELETE HTTP call without body
         */
        @NonNull
        ClientRequest delete();

        /**
         * Build a {@link ClientRequest} for a DELETE HTTP call
         */
        @NonNull
        ClientRequest delete(final @NonNull ClientRequestBody requestBody);

        /**
         * Build a {@link ClientRequest} for a PUT HTTP call
         */
        @NonNull
        ClientRequest put(final @NonNull ClientRequestBody requestBody);

        /**
         * Build a {@link ClientRequest} for a PATCH HTTP call
         */
        @NonNull
        ClientRequest patch(final @NonNull ClientRequestBody requestBody);
    }
}
