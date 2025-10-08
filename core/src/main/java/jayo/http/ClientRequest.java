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
import java.util.Objects;

/**
 * An HTTP client request. Instances of this class are immutable if their {@linkplain #getBody() body} is null or itself
 * immutable.
 */
public sealed interface ClientRequest permits RealClientRequest {
    static @NonNull ClientRequest get(final @NonNull HttpUrl url) {
        Objects.requireNonNull(url);
        return builder().url(url).get();
    }

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
    @Nullable
    Object tag();

    /**
     * @return a builder based on this request.
     */
    @NonNull
    FromClientRequestBuilder newBuilder();

    /**
     * @return a cURL command equivalent to this request, useful for debugging and reproducing requests.
     * <p>
     * This includes the HTTP method, headers, request body (if present), and URL.
     * <p>
     * Example:
     * <pre>
     * {@code
     * curl 'https://example.com/api' \
     * -X PUT \
     * -H 'Authorization: Bearer token' \
     * --data '{\"key\":\"value\"}'
     * }
     * </pre>
     * <b>Note:</b> This will consume the request body. This may have side effects if the {@link ClientRequestBody} is
     * streaming or can be consumed only once.
     */
    @NonNull
    String toCurl(final boolean includeBody);

    /**
     * The abstract builder used to create a {@link ClientRequest} instance.
     */
    sealed interface AbstractBuilder<T extends AbstractBuilder<T>>
            permits Builder, FromClientRequestBuilder, RealClientRequest.AbstractBuilder {
        /**
         * Sets the URL target of this request.
         */
        @NonNull
        T url(final @NonNull HttpUrl url);

        /**
         * Sets the URL target of this request.
         *
         * @throws IllegalArgumentException if {@code url} is not a valid HTTP or HTTPS URL. Avoid this exception by
         *                                  calling {@link HttpUrl#parse(String)}; it returns null for invalid URLs.
         */
        @NonNull
        T url(final @NonNull String url);

        /**
         * Sets the URL target of this request.
         *
         * @throws IllegalArgumentException if the scheme of {@code url} is not {@code http} or {@code https}.
         */
        @NonNull
        T url(final @NonNull URL url);

        /**
         * Sets the header named {@code name} to {@code value}. If this request already has any headers with that name,
         * they are all replaced.
         */
        @NonNull
        T header(final @NonNull String name, final @NonNull String value);

        /**
         * Adds a header with {@code name} to {@code value}. Prefer this method for multiply valued headers like
         * "Set-Cookie".
         * <p>
         * Note that for some headers including {@code Content-Length} and {@code Content-Encoding}, Jayo HTTP may
         * replace {@code value} with a header derived from the request body.
         */
        @NonNull
        T addHeader(final @NonNull String name, final @NonNull String value);

        /**
         * Removes all headers named {@code name} on this builder.
         */
        @NonNull
        T removeHeader(final @NonNull String name);

        /**
         * Remove all headers on this builder and adds {@code headers}.
         */
        @NonNull
        T headers(final @NonNull Headers headers);

        /**
         * Sets this request's {@code Cache-Control} header, replacing any cache control headers already present. If
         * {@code cacheControl} doesn't define any directives, this clears this request's cache-control headers.
         */
        @NonNull
        T cacheControl(final @NonNull CacheControl cacheControl);

        /**
         * Attaches {@code tag} to the request using {@code type} as a key. Tags can be read from a request using
         * {@link ClientRequest#tag(Class)}. Use null to remove any existing tag assigned for {@code type}.
         * <p>
         * Use this API to attach timing, debugging, or other application data to a request so that you may read it in
         * interceptors, event listeners, or callbacks.
         */
        @NonNull
        <U> T tag(final @NonNull Class<U> type, final @Nullable U tag);

        /**
         * Attaches {@code tag} to the request using `Object.class` as a key.
         */
        @NonNull
        T tag(final @Nullable Object tag);

        /**
         * Override the {@linkplain ClientRequest#getUrl() ClientRequest.getUrl()} for caching, if it is either polluted
         * with transient query params, or has a canonical URL possibly for a CDN.
         * <p>
         * Note that POST requests will not be sent to the server if this URL is set and matches a cached response.
         */
        @NonNull
        T cacheUrlOverride(final @Nullable HttpUrl cacheUrlOverride);

        /**
         * When set to {@code true}, configures this request's body to be compressed when it is transmitted. Default is
         * false. This also adds the {@code Content-Encoding: gzip} header. If a {@code Content-Encoding} header was
         * already present, it is discarded and replaced by {@code gzip} value.
         * <ul>
         * <li>Only use this method if you have prior knowledge that the receiving server supports gzip-compressed
         * requests.
         * <li>This option is no-op if this request doesn't have a request body.
         * </ul>
         */
        @NonNull
        T gzip(final boolean gzip);

        /**
         * Build a {@link ClientRequest} for a {@code method} HTTP call. You may provide a non-null {@code body}.
         *
         * @throws IllegalArgumentException if {@code body} is non-null for a {@code method} that does not permit one,
         *                                  or if {@code body} is null for a {@code method} that requires one.
         */
        @NonNull
        ClientRequest method(final @NonNull String method, final @Nullable ClientRequestBody body);
    }

    /**
     * The builder used to create a {@link ClientRequest} instance.
     */
    sealed interface Builder extends AbstractBuilder<Builder> permits RealClientRequest.Builder {
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

        /**
         * Build a {@link ClientRequest} for a QUERY HTTP call
         * <p>
         * By default, {@code QUERY} requests are not cached. You can use {@link #cacheUrlOverride(HttpUrl)} to specify
         * how to cache them.
         * <p>
         * A typical use case is to hash the request body:
         * <pre>
         * {@code
         *     Buffer buffer = Buffer.create();
         *     requestBody.writeTo(buffer);
         *     String hash = Jayo.hash(buffer, JdkDigest.SHA_256).hex();
         *     val query = ClientRequest.builder()
         *         .query(requestBody)
         *         .url("https://example.com/query")
         *         .cacheUrlOverride(HttpUrl.get("https://example.com/query/" + hash))
         *         .build();
         * }
         * </pre>
         *
         * @see #cacheUrlOverride(HttpUrl)
         */
        @NonNull
        ClientRequest query(final @NonNull ClientRequestBody requestBody);

        /**
         * Build a {@link ClientRequest} for a CONNECT HTTP call
         */
        @NonNull
        ClientRequest connect();
    }

    /**
     * The builder used to create a {@link ClientRequest} instance from an existing {@link ClientRequest}.
     */
    sealed interface FromClientRequestBuilder extends AbstractBuilder<FromClientRequestBuilder>
            permits RealClientRequest.FromClientRequestBuilder {
        @NonNull
        ClientRequest build();
    }
}
