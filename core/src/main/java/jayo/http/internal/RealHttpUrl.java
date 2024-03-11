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

import jayo.http.HttpUrl;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;
import java.util.function.Consumer;

public sealed class RealHttpUrl implements HttpUrl permits RealHttpsUrl {
    private final @NonNull String scheme;
    private final @NonNull String username;
    private final @NonNull String password;
    private final @NonNull String host;
    private final int port;
    private final @Nullable String fragment;
    private final @NonNull List<@NonNull String> pathSegments;
    /**
     * Alternating, decoded query names and final varues, or null for no query. Names may be empty or non-empty, but never
     * null. final varues are null if the name has no corresponding '=' separator, or empty, or non-empty.
     */
    private final @Nullable List<@Nullable String> queryNamesAndValues;
    /**
     * Canonical URL.
     */
    private final @NonNull String url;

    public RealHttpUrl(
            final @NonNull String scheme,
            final @NonNull String username,
            final @NonNull String password,
            final @NonNull String host,
            final int port,
            final @Nullable String fragment,
            final @NonNull List<@NonNull String> pathSegments,
            final @Nullable List<@Nullable String> queryNamesAndValues,
            final @NonNull String url
    ) {
        this.scheme = scheme;
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
    public @NonNull String getScheme() {
        return scheme;
    }

    @Override
    public boolean isHttps() {
        return scheme.equals("https");
    }

    @Override
    public final @NonNull String getUsername() {
        return username;
    }

    @Override
    public final @NonNull String getPassword() {
        return password;
    }

    @Override
    public final @NonNull String getHost() {
        return host;
    }

    @Override
    public final int getPort() {
        return port;
    }

    @Override
    public final @NonNull List<@NonNull String> getPathSegments() {
        return pathSegments;
    }

    @Override
    public final @Nullable String getFragment() {
        return fragment;
    }

    @Override
    public final @NonNull URL toUrl() {
        try {
            return toUri().toURL();
        } catch (MalformedURLException e) {
            throw new RuntimeException(e); // Unexpected!
        }
    }

    @Override
    public final @NonNull URI toUri() {
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
                final var stripped = uri.replaceAll(
                        "[\\u0000-\\u001F\\u007F-\\u009F\\p{javaWhitespace}]", "");
                return URI.create(stripped);
            } catch (Exception _ignored) {
                throw new RuntimeException(e); // Unexpected!
            }
        }
    }

    @Override
    public final @NonNull String getEncodedUsername() {
        if (username.isBlank()) {
            return "";
        }
        final var usernameStart = scheme.length() + 3; // "://".length() == 3.
        final var usernameEnd = UtilCommon.delimiterOffset(url, ":@", usernameStart, url.length());
        return url.substring(usernameStart, usernameEnd);
    }

    @Override
    public final @NonNull String getEncodedPassword() {
        if (password.isBlank()) {
            return "";
        }
        final var passwordStart = url.indexOf(':', scheme.length() + 3) + 1;
        final var passwordEnd = url.indexOf('@');
        return url.substring(passwordStart, passwordEnd);
    }

    @Override
    public final @NonNull String getEncodedPath() {
        final var pathStart = url.indexOf('/', scheme.length() + 3); // "://".length() == 3.
        final var pathEnd = UtilCommon.delimiterOffset(url, "?#", pathStart, url.length());
        return url.substring(pathStart, pathEnd);
    }

    @Override
    public final @NonNull List<String> getEncodedPathSegments() {
        final var pathStart = url.indexOf('/', scheme.length() + 3);
        final var pathEnd = UtilCommon.delimiterOffset(url, "?#", pathStart, url.length());
        final var result = new ArrayList<String>();
        var i = pathStart;
        while (i < pathEnd) {
            i++; // Skip the '/'.
            final var segmentEnd = UtilCommon.delimiterOffset(url, '/', i, pathEnd);
            result.add(url.substring(i, segmentEnd));
            i = segmentEnd;
        }
        return result;
    }

    @Override
    public final @Nullable String getEncodedQuery() {
        if (queryNamesAndValues == null) {
            return null; // No query.
        }
        final var queryStart = url.indexOf('?') + 1;
        final var queryEnd = UtilCommon.delimiterOffset(url, '#', queryStart, url.length());
        return url.substring(queryStart, queryEnd);
    }

    @Override
    public final int getPathSize() {
        return pathSegments.size();
    }

    @Override
    public final @Nullable String getQuery() {
        if (queryNamesAndValues == null) {
            return null; // No query.
        }
        final var result = new StringBuilder();
        toQueryString(queryNamesAndValues, result);
        return result.toString();
    }

    @Override
    public final int getQuerySize() {
        return (queryNamesAndValues != null) ? queryNamesAndValues.size() / 2 : 0;
    }

    @Override
    public final @Nullable String getQueryParameter(@NonNull String name) {
        if (queryNamesAndValues == null) {
            return null;
        }
        int i = 0;
        while (i < queryNamesAndValues.size()) {
            if (name.equals(queryNamesAndValues.get(i))) {
                return queryNamesAndValues.get(i + 1);
            }
            i += 2;
        }
        return null;
    }

    @Override
    public final @NonNull Set<@NonNull String> getQueryParameterNames() {
        if (queryNamesAndValues == null) {
            return Set.of();
        }
        final var result = new LinkedHashSet<String>();
        int i = 0;
        while (i < queryNamesAndValues.size()) {
            result.add(queryNamesAndValues.get(i));
            i += 2;
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public final @NonNull List<@Nullable String> getQueryParameterValues(@NonNull String name) {
        if (queryNamesAndValues == null) {
            return List.of();
        }
        final var result = new ArrayList<String>();
        int i = 0;
        while (i < queryNamesAndValues.size()) {
            if (name.equals(queryNamesAndValues.get(i))) {
                result.add(queryNamesAndValues.get(i + 1));
            }
            i += 2;
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public final @NonNull String getQueryParameterName(int index) {
        if (queryNamesAndValues == null) {
            throw new IndexOutOfBoundsException();
        }
        final var queryParameterName = queryNamesAndValues.get(index * 2);
        assert queryParameterName != null;
        return queryParameterName;
    }

    @Override
    public final @Nullable String getQueryParameterValue(int index) {
        if (queryNamesAndValues == null) {
            throw new IndexOutOfBoundsException();
        }
        return queryNamesAndValues.get(index * 2 + 1);
    }

    @Override
    public final @Nullable String getEncodedFragment() {
        if (fragment == null) {
            return null;
        }
        final var fragmentStart = url.indexOf('#') + 1;
        return url.substring(fragmentStart);
    }

    @Override
    public final @NonNull String redact() {
        return null;
    }

    @Override
    public final @Nullable HttpUrl resolve(@NonNull String link) {
        return null;
    }

    @Override
    public final @Nullable String topPrivateDomain() {
        return null;
    }

    @Override
    public final @NonNull HttpUrl createNew(@NonNull Consumer<Config> configurer) {
        return null;
    }

    /**
     * Appends this list of query names and values to a StringBuilder.
     */
    private static void toQueryString(List<String> queryNamesAndValues, StringBuilder out) {
        int i = 0;
        while (i < queryNamesAndValues.size()) {
            final var name = queryNamesAndValues.get(i++);
            final var value = queryNamesAndValues.get(i++);
            if (i > 2) {
                out.append('&');
            }
            out.append(name);
            if (value != null) {
                out.append('=');
                out.append(value);
            }
        }
    }
}
