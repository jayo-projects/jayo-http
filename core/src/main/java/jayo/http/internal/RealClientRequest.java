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

import jayo.http.*;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static jayo.http.internal.Utils.isSensitiveHeader;
import static jayo.http.internal.Utils.startsWithIgnoreCase;

public final class RealClientRequest implements ClientRequest {
    private final @NonNull AbstractBuilder<?> builder;
    final @NonNull Map<Class<?>, ?> tags;
    private @Nullable CacheControl lazyCacheControl = null;

    RealClientRequest(final @NonNull AbstractBuilder<?> builder) {
        this.builder = Objects.requireNonNull(builder);
        this.tags = Map.copyOf(builder.tags);
    }

    @Override
    public @NonNull HttpUrl getUrl() {
        return Objects.requireNonNull(builder.url, "url == null");
    }

    @Override
    public @NonNull String getMethod() {
        assert builder.method != null;
        return builder.method;
    }

    @Override
    public @NonNull Headers getHeaders() {
        return builder.headers.build();
    }

    @Override
    public @Nullable ClientRequestBody getBody() {
        return builder.body;
    }

    @Override
    public @Nullable HttpUrl getCacheUrlOverride() {
        return builder.cacheUrlOverride;
    }

    @Override
    public boolean isHttps() {
        return getUrl().isHttps();
    }

    @Override
    public @Nullable String header(final @NonNull String name) {
        Objects.requireNonNull(name);
        return getHeaders().get(name);
    }

    @Override
    public @NonNull List<String> headers(final @NonNull String name) {
        Objects.requireNonNull(name);
        return getHeaders().values(name);
    }

    @Override
    public @NonNull CacheControl getCacheControl() {
        if (lazyCacheControl == null) {
            lazyCacheControl = CacheControl.parse(getHeaders());
        }
        return lazyCacheControl;
    }

    @Override
    public <T> @Nullable T tag(final @NonNull Class<T> type) {
        Objects.requireNonNull(type);
        return type.cast(tags.get(type));
    }

    @Override
    public @Nullable Object tag() {
        return tag(Object.class);
    }

    @Override
    public @NonNull FromClientRequestBuilder newBuilder() {
        return new FromClientRequestBuilder(this);
    }

    @Override
    public @NonNull String toString() {
        final var sb = new StringBuilder("ClientRequest{");
        sb.append("method=").append(getMethod());
        sb.append(", url=").append(getUrl());
        final var headers = getHeaders();
        if (!headers.isEmpty()) {
            sb.append(", headers=[");
            for (var index = 0; index < headers.size(); index++) {
                if (index > 0) {
                    sb.append(", ");
                }
                final var name = headers.name(index);
                sb.append(name);
                sb.append(':');
                sb.append((isSensitiveHeader(name)) ? "██" : headers.value(index));
            }
            sb.append(']');
        }
        if (!tags.isEmpty()) {
            sb.append(", tags=");
            sb.append(tags);
        }
        sb.append('}');

        return sb.toString();
    }

    public static abstract sealed class AbstractBuilder<T extends ClientRequest.AbstractBuilder<T>>
            implements ClientRequest.AbstractBuilder<T> {
        @Nullable
        HttpUrl url = null;
        @Nullable
        String method = null;
        Headers.@NonNull Builder headers;
        @Nullable
        ClientRequestBody body = null;
        @Nullable
        HttpUrl cacheUrlOverride = null;
        @NonNull
        Map<Class<?>, Object> tags = new HashMap<>();

        AbstractBuilder() {
            this.headers = Headers.builder();
        }

        abstract @NonNull T getThis();

        @Override
        public final @NonNull T url(final @NonNull HttpUrl url) {
            this.url = Objects.requireNonNull(url);
            return getThis();
        }

        @Override
        public final @NonNull T url(final @NonNull String url) {
            url(HttpUrl.get(canonicalUrl(url)));
            return getThis();
        }

        @Override
        public final @NonNull T url(final @NonNull URL url) {
            url(HttpUrl.get(url.toString()));
            return getThis();
        }

        @Override
        public final @NonNull T header(final @NonNull String name, final @NonNull String value) {
            headers.set(name, value);
            return getThis();
        }

        @Override
        public final @NonNull T addHeader(final @NonNull String name, final @NonNull String value) {
            headers.add(name, value);
            return getThis();
        }

        @Override
        public final @NonNull T removeHeader(final @NonNull String name) {
            headers.removeAll(name);
            return getThis();
        }

        @Override
        public final @NonNull T cacheControl(final @NonNull CacheControl cacheControl) {
            Objects.requireNonNull(cacheControl);
            final var value = cacheControl.toString();
            if (value.isEmpty()) {
                removeHeader("Cache-Control");
            } else {
                header("Cache-Control", value);
            }
            return getThis();
        }

        @Override
        public final @NonNull <U> T tag(final @NonNull Class<U> type, final @Nullable U tag) {
            Objects.requireNonNull(type);
            if (tag == null) {
                tags.remove(type);
            } else {
                tags.put(type, tag);
            }
            return getThis();
        }

        @Override
        public final @NonNull T tag(final @Nullable Object tag) {
            return tag(Object.class, tag);
        }

        @Override
        public final @NonNull T cacheUrlOverride(final @Nullable HttpUrl cacheUrlOverride) {
            this.cacheUrlOverride = cacheUrlOverride;
            return getThis();
        }

        final @NonNull ClientRequest buildInternal() {
            return new RealClientRequest(this);
        }

        private static @NonNull String canonicalUrl(final @NonNull String url) {
            assert url != null;
            // Silently replace web socket URLs with HTTP URLs.
            if (startsWithIgnoreCase(url, "ws:")) {
                return "http:" + url.substring(3);
            }
            if (startsWithIgnoreCase(url, "wss:")) {
                return "https:" + url.substring(4);
            }
            return url;
        }
    }

    public static final class Builder extends AbstractBuilder<ClientRequest.Builder> implements ClientRequest.Builder {
        @Override
        public @NonNull Builder headers(final @NonNull Headers headers) {
            Objects.requireNonNull(headers);
            this.headers = headers.newBuilder();
            return this;
        }

        @Override
        public @NonNull ClientRequest get() {
            method = "GET";
            return buildInternal();
        }

        @Override
        public @NonNull ClientRequest head() {
            method = "HEAD";
            return buildInternal();
        }

        @Override
        public @NonNull ClientRequest post(final @NonNull ClientRequestBody requestBody) {
            Objects.requireNonNull(requestBody);
            method = "POST";
            body = requestBody;
            return buildInternal();
        }

        @Override
        public @NonNull ClientRequest delete() {
            method = "DELETE";
            return buildInternal();
        }

        @Override
        public @NonNull ClientRequest delete(final @NonNull ClientRequestBody requestBody) {
            Objects.requireNonNull(requestBody);
            method = "DELETE";
            body = requestBody;
            return buildInternal();
        }

        @Override
        public @NonNull ClientRequest put(final @NonNull ClientRequestBody requestBody) {
            Objects.requireNonNull(requestBody);
            method = "PUT";
            body = requestBody;
            return buildInternal();
        }

        @Override
        public @NonNull ClientRequest patch(final @NonNull ClientRequestBody requestBody) {
            Objects.requireNonNull(requestBody);
            method = "PATCH";
            body = requestBody;
            return buildInternal();
        }

        @Override
        @NonNull
        Builder getThis() {
            return this;
        }
    }

    public static final class FromClientRequestBuilder extends AbstractBuilder<ClientRequest.FromClientRequestBuilder>
            implements ClientRequest.FromClientRequestBuilder {

        private FromClientRequestBuilder(final @NonNull ClientRequest request) {
            if (!(request instanceof RealClientRequest _request)) {
                throw new IllegalArgumentException();
            }

            this.url = request.getUrl();
            this.method = request.getMethod();
            this.body = request.getBody();
            final var requestTags = _request.tags;
            if (requestTags.isEmpty()) {
                this.tags = new HashMap<>();
            } else {
                this.tags = new HashMap<>(requestTags);
            }
            this.headers = request.getHeaders().newBuilder();
            this.cacheUrlOverride = request.getCacheUrlOverride();
        }

        @Override
        public @NonNull ClientRequest build() {
            return buildInternal();
        }

        @Override
        @NonNull
        FromClientRequestBuilder getThis() {
            return this;
        }
    }
}
