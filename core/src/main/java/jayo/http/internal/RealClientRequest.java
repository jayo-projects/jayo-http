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
    private final @NonNull String method;
    private final @NonNull Builder builder;
    private final @Nullable ClientRequestBody body;
    final @NonNull Map<Class<?>, ?> tags;
    private @Nullable CacheControl lazyCacheControl = null;

    RealClientRequest(final @NonNull String method,
                      final @NonNull Builder builder,
                      final @Nullable ClientRequestBody body) {
        this.method = Objects.requireNonNull(method);
        this.builder = Objects.requireNonNull(builder);
        this.body = body;
        this.tags = Map.copyOf(builder.tags);
    }

    @Override
    public @NonNull HttpUrl getUrl() {
        return Objects.requireNonNull(builder.url, "url == null");
    }

    @Override
    public @NonNull String getMethod() {
        return method;
    }

    @Override
    public @NonNull Headers getHeaders() {
        return builder.headers.build();
    }

    @Override
    public @Nullable ClientRequestBody getBody() {
        return body;
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
        var result = lazyCacheControl;
        if (result == null) {
            result = CacheControl.parse(getHeaders());
            lazyCacheControl = result;
        }
        return result;
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
    public @NonNull String toString() {
        final var sb = new StringBuilder("Request{method=");
        sb.append(method);
        sb.append(", url=");
        sb.append(getUrl());
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

    public static final class Builder implements ClientRequest.Builder {
        private @Nullable HttpUrl url = null;
        private Headers.@NonNull Builder headers = Headers.builder();
        private @Nullable HttpUrl cacheUrlOverride = null;
        private final @NonNull Map<Class<?>, Object> tags = new HashMap<>();

        @Override
        public ClientRequest.@NonNull Builder url(final @NonNull HttpUrl url) {
            this.url = Objects.requireNonNull(url);
            return this;
        }

        @Override
        public ClientRequest.@NonNull Builder url(final @NonNull String url) {
            url(HttpUrl.get(canonicalUrl(url)));
            return this;
        }

        @Override
        public ClientRequest.@NonNull Builder url(final @NonNull URL url) {
            url(HttpUrl.get(url.toString()));
            return this;
        }

        @Override
        public ClientRequest.@NonNull Builder header(final @NonNull String name, final @NonNull String value) {
            headers.set(name, value);
            return this;
        }

        @Override
        public ClientRequest.@NonNull Builder addHeader(final @NonNull String name, final @NonNull String value) {
            headers.add(name, value);
            return this;
        }

        @Override
        public ClientRequest.@NonNull Builder removeHeader(final @NonNull String name) {
            headers.removeAll(name);
            return this;
        }

        @Override
        public ClientRequest.@NonNull Builder headers(final @NonNull Headers headers) {
            Objects.requireNonNull(headers);
            this.headers = headers.newBuilder();
            return this;
        }

        @Override
        public ClientRequest.@NonNull Builder cacheControl(final @NonNull CacheControl cacheControl) {
            Objects.requireNonNull(cacheControl);
            final var value = cacheControl.toString();
            if (value.isEmpty()) {
                removeHeader("Cache-Control");
            } else {
                header("Cache-Control", value);
            }
            return this;
        }

        @Override
        public <T> ClientRequest.@NonNull Builder tag(final @NonNull Class<T> type, final @Nullable T tag) {
            Objects.requireNonNull(type);
            if (tag == null) {
                tags.remove(type);
            } else {
                tags.put(type, tag);
            }
            return this;
        }

        @Override
        public ClientRequest.@NonNull Builder tag(final @Nullable Object tag) {
            return tag(Object.class, tag);
        }

        @Override
        public ClientRequest.@NonNull Builder cacheUrlOverride(final @Nullable HttpUrl cacheUrlOverride) {
            this.cacheUrlOverride = cacheUrlOverride;
            return this;
        }

        @Override
        public @NonNull ClientRequest get() {
            return new RealClientRequest("GET", this, null);
        }

        @Override
        public @NonNull ClientRequest head() {
            return new RealClientRequest("HEAD", this, null);
        }

        @Override
        public @NonNull ClientRequest post(final @NonNull ClientRequestBody requestBody) {
            Objects.requireNonNull(requestBody);
            return new RealClientRequest("POST", this, requestBody);
        }

        @Override
        public @NonNull ClientRequest delete() {
            return new RealClientRequest("DELETE", this, null);
        }

        @Override
        public @NonNull ClientRequest delete(final @NonNull ClientRequestBody requestBody) {
            Objects.requireNonNull(requestBody);
            return new RealClientRequest("DELETE", this, requestBody);
        }

        @Override
        public @NonNull ClientRequest put(final @NonNull ClientRequestBody requestBody) {
            Objects.requireNonNull(requestBody);
            return new RealClientRequest("PUT", this, requestBody);
        }

        @Override
        public @NonNull ClientRequest patch(final @NonNull ClientRequestBody requestBody) {
            Objects.requireNonNull(requestBody);
            return new RealClientRequest("PATCH", this, requestBody);
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
}
