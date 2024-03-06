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

import jayo.http.HttpsUrl;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public final class RealHttpsUrl implements HttpsUrl {
    private final @NonNull String username;
    private final @NonNull String password;
    private final @NonNull String host;
    private final int port;
    private final @Nullable String fragment;
    private final @NonNull List<@NonNull String> pathSegments;
    /**
     * Alternating, decoded query names and values, or null for no query. Names may be empty or non-empty, but never
     * null. Values are null if the name has no corresponding '=' separator, or empty, or non-empty.
     */
    private final @Nullable List<@Nullable String> queryNamesAndValues;
    /**
     * Canonical URL.
     */
    private final @NonNull String url;

    public RealHttpsUrl(
            @NonNull String username,
            @NonNull String password,
            @NonNull String host,
            int port,
            @Nullable String fragment,
            @NonNull List<@NonNull String> pathSegments,
            @Nullable List<@Nullable String> queryNamesAndValues,
            @NonNull String url
    ) {
        this.username = username;
        this.password = password;
        this.host = host;
        this.port = port;
        this.fragment = fragment;
        this.pathSegments = pathSegments;
        this.queryNamesAndValues = queryNamesAndValues;
        this.url = url;
    }

    @Override
    public @NonNull String getUsername() {
        return username;
    }

    @Override
    public @NonNull String getPassword() {
        return password;
    }

    @Override
    public @NonNull String getHost() {
        return host;
    }

    @Override
    public int getPort() {
        return port;
    }

    @Override
    public @NonNull List<@NonNull String> getPathSegments() {
        return pathSegments;
    }

    @Override
    public @Nullable String getFragment() {
        return fragment;
    }

    @Override
    public @NonNull URL toUrl() {
        try {
            return toUri().toURL();
        } catch (MalformedURLException e) {
            throw new RuntimeException(e); // Unexpected!
        }
    }

    @Override
    public @NonNull URI toUri() {
        StringBuilder uriBuilder = new StringBuilder();
        //noinspection ResultOfMethodCallIgnored
        createNew(config -> {
            config.reencodeForUri();
            uriBuilder.append(config);
        });
        final var uri = uriBuilder.toString();
        try {
            return new URI(uri);
        } catch (URISyntaxException e) {
            // Unlikely edge case: the URI has a forbidden character in the fragment. Strip it & retry.
            try {
                final var stripped = uri.replace(
                        "[\\u0000-\\u001F\\u007F-\\u009F\\p{javaWhitespace}]", "");
                return URI.create(stripped);
            } catch (Exception _ignored) {
                throw new RuntimeException(e); // Unexpected!
            }
        }
    }

    @Override
    public @NonNull String getEncodedUsername() {
        return null;
    }

    @Override
    public @NonNull String getEncodedPassword() {
        return null;
    }

    @Override
    public @NonNull String getEncodedPath() {
        return null;
    }

    @Override
    public @NonNull List<String> getEncodedPathSegments() {
        return null;
    }

    @Override
    public @Nullable String getEncodedQuery() {
        return null;
    }

    @Override
    public int getPathSize() {
        return 0;
    }

    @Override
    public @Nullable String getQuery() {
        return null;
    }

    @Override
    public int getQuerySize() {
        return 0;
    }

    @Override
    public @Nullable String getQueryParameter(@NonNull String name) {
        return null;
    }

    @Override
    public @NonNull Set<@NonNull String> getQueryParameterNames() {
        return null;
    }

    @Override
    public @NonNull List<@Nullable String> getQueryParameterValues(@NonNull String name) {
        return null;
    }

    @Override
    public @NonNull String getQueryParameterName(int index) {
        return null;
    }

    @Override
    public @Nullable String getQueryParameterValue(int index) {
        return null;
    }

    @Override
    public @Nullable String getEncodedFragment() {
        return null;
    }

    @Override
    public @NonNull String redact() {
        return null;
    }

    @Override
    public @Nullable HttpsUrl resolve(@NonNull String link) {
        return null;
    }

    @Override
    public @Nullable String topPrivateDomain() {
        return null;
    }

    @Override
    public @NonNull HttpsUrl createNew(@NonNull Consumer<Config> configurer) {
        return null;
    }
}
