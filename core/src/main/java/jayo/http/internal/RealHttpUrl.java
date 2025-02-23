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

import jayo.JayoException;
import jayo.http.HttpUrl;
import jayo.http.internal.publicsuffix.PublicSuffixDatabase;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;

import static jayo.http.HttpUrl.defaultPort;
import static jayo.http.internal.HostnameUtils.toCanonicalHost;
import static jayo.http.internal.UrlUtils.*;
import static jayo.http.internal.Utils.*;
import static jayo.tools.HostnameUtils.canParseAsIpAddress;

public final class RealHttpUrl implements HttpUrl {
    private final @NonNull String scheme;
    private final @NonNull String username;
    private final @NonNull String password;
    private final @NonNull String host;
    private final int port;
    private final @NonNull List<@NonNull String> pathSegments;
    /**
     * Alternating, decoded query names and final varues, or null for no query. Names may be empty or non-empty, but never
     * null. final varues are null if the name has no corresponding '=' separator, or empty, or non-empty.
     */
    private final @Nullable List<@Nullable String> queryNamesAndValues;
    private final @Nullable String fragment;
    /**
     * Canonical URL.
     */
    private final @NonNull String url;

    private RealHttpUrl(
            final @NonNull String scheme,
            final @NonNull String username,
            final @NonNull String password,
            final @NonNull String host,
            final int port,
            final @NonNull List<@NonNull String> pathSegments,
            final @Nullable List<@Nullable String> queryNamesAndValues,
            final @Nullable String fragment,
            final @NonNull String url
    ) {
        this.scheme = scheme;
        this.username = username;
        this.password = password;
        this.host = host;
        this.port = port;
        this.pathSegments = pathSegments;
        this.queryNamesAndValues = queryNamesAndValues;
        this.fragment = fragment;
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
            return new URL(url);
        } catch (MalformedURLException e) {
            throw JayoException.buildJayoException(e);
        }
    }

    @Override
    public @NonNull URI toUri() {
        final var uri = ((Builder) newBuilder()).reencodeForUri().toString();
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
    public @NonNull String getEncodedUsername() {
        if (username.isEmpty()) {
            return "";
        }
        final var usernameStart = scheme.length() + 3; // "://".length() == 3.
        final var usernameEnd = delimiterOffset(url, ":@", usernameStart, url.length());
        return url.substring(usernameStart, usernameEnd);
    }

    @Override
    public @NonNull String getEncodedPassword() {
        if (password.isEmpty()) {
            return "";
        }
        final var passwordStart = url.indexOf(':', scheme.length() + 3) + 1;
        final var passwordEnd = url.indexOf('@');
        return url.substring(passwordStart, passwordEnd);
    }

    @Override
    public @NonNull String getEncodedPath() {
        final var pathStart = url.indexOf('/', scheme.length() + 3); // "://".length() == 3.
        final var pathEnd = delimiterOffset(url, "?#", pathStart, url.length());
        return url.substring(pathStart, pathEnd);
    }

    @Override
    public @NonNull List<String> getEncodedPathSegments() {
        final var pathStart = url.indexOf('/', scheme.length() + 3);
        final var pathEnd = delimiterOffset(url, "?#", pathStart, url.length());
        final var result = new ArrayList<String>();
        var i = pathStart;
        while (i < pathEnd) {
            i++; // Skip the '/'.
            final var segmentEnd = delimiterOffset(url, '/', i, pathEnd);
            result.add(url.substring(i, segmentEnd));
            i = segmentEnd;
        }
        return result;
    }

    @Override
    public @Nullable String getEncodedQuery() {
        if (queryNamesAndValues == null) {
            return null; // No query.
        }
        final var queryStart = url.indexOf('?') + 1;
        final var queryEnd = delimiterOffset(url, '#', queryStart, url.length());
        return url.substring(queryStart, queryEnd);
    }

    @Override
    public int getPathSize() {
        return pathSegments.size();
    }

    @Override
    public @Nullable String getQuery() {
        if (queryNamesAndValues == null) {
            return null; // No query.
        }
        final var result = new StringBuilder();
        toQueryString(queryNamesAndValues, result);
        return result.toString();
    }

    @Override
    public int getQuerySize() {
        return (queryNamesAndValues != null) ? queryNamesAndValues.size() / 2 : 0;
    }

    @Override
    public @Nullable String queryParameter(final @NonNull String name) {
        if (queryNamesAndValues == null) {
            return null;
        }
        for (var i = 0; i < queryNamesAndValues.size(); i += 2) {
            if (name.equals(queryNamesAndValues.get(i))) {
                return queryNamesAndValues.get(i + 1);
            }
        }
        return null;
    }

    @Override
    public @NonNull Set<@NonNull String> getQueryParameterNames() {
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
    public @NonNull List<@Nullable String> queryParameterValues(final @NonNull String name) {
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
    public @NonNull String queryParameterName(final int index) {
        if (queryNamesAndValues == null) {
            throw new IndexOutOfBoundsException();
        }
        final var queryParameterName = queryNamesAndValues.get(index * 2);
        assert queryParameterName != null;
        return queryParameterName;
    }

    @Override
    public @Nullable String queryParameterValue(final int index) {
        if (queryNamesAndValues == null) {
            throw new IndexOutOfBoundsException();
        }
        return queryNamesAndValues.get(index * 2 + 1);
    }

    @Override
    public @Nullable String getEncodedFragment() {
        if (fragment == null) {
            return null;
        }
        final var fragmentStart = url.indexOf('#') + 1;
        return url.substring(fragmentStart);
    }

    @Override
    public @NonNull String redact() {
        final var newBuilder = newBuilder("/...");
        assert newBuilder != null;
        return newBuilder
                .username("")
                .password("")
                .build()
                .toString();
    }

    @Override
    public @Nullable HttpUrl resolve(final @NonNull String link) {
        final var newBuilder = newBuilder(link);
        return (newBuilder != null) ? newBuilder.build() : null;
    }

    @Override
    public @Nullable String topPrivateDomain() {
        if (canParseAsIpAddress(host)) {
            return null;
        }
        return PublicSuffixDatabase.getInstance().getEffectiveTldPlusOne(host);
    }

    @Override
    public HttpUrl.@NonNull Builder newBuilder() {
        final var result = new Builder();
        result.scheme = scheme;
        result.encodedUsername = getEncodedUsername();
        result.encodedPassword = getEncodedPassword();
        result.host = host;
        // If we're set to a default port, unset it in case of a scheme change.
        result.port = (port != defaultPort(scheme)) ? port : -1;
        result.encodedPathSegments.clear();
        result.encodedPathSegments.addAll(getEncodedPathSegments());
        result.encodedQuery(getEncodedQuery());
        result.encodedFragment = getEncodedFragment();
        return result;
    }

    @Override
    public HttpUrl.@Nullable Builder newBuilder(@NonNull String link) {
        Objects.requireNonNull(link);
        try {
            return new Builder().parse(this, link);
        } catch (IllegalArgumentException _unused) {
            return null;
        }
    }

    @Override
    public boolean equals(final @Nullable Object other) {
        if (!(other instanceof RealHttpUrl that)) {
            return false;
        }

        return url.equals(that.url);
    }

    @Override
    public int hashCode() {
        return url.hashCode();
    }

    @Override
    public @NonNull String toString() {
        return url;
    }

    public static final class Builder implements HttpUrl.Builder {
        private @Nullable String scheme = null;
        private @NonNull String encodedUsername = "";
        private @NonNull String encodedPassword = "";
        private @Nullable String host = null;
        private int port = -1;
        private final @NonNull List<@NonNull String> encodedPathSegments;
        private @Nullable List<@Nullable String> encodedQueryNamesAndValues = null;
        private @Nullable String encodedFragment = null;

        public Builder() {
            encodedPathSegments = new ArrayList<>();
            encodedPathSegments.add("");
        }

        @Override
        public HttpUrl.@NonNull Builder scheme(final @NonNull String scheme) {
            Objects.requireNonNull(scheme);
            if (scheme.equalsIgnoreCase("https")) {
                this.scheme = "https";
                return this;
            }
            if (scheme.equalsIgnoreCase("http")) {
                this.scheme = "http";
                return this;
            }
            throw new IllegalArgumentException("unexpected scheme: " + scheme);
        }

        @Override
        public HttpUrl.@NonNull Builder host(final @NonNull String host) {
            Objects.requireNonNull(host);
            final var encoded = toCanonicalHost(percentDecode(host));
            if (encoded == null) {
                throw new IllegalArgumentException("unexpected host: " + host);
            }
            this.host = encoded;
            return this;
        }

        @Override
        public HttpUrl.@NonNull Builder port(final int port) {
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("unexpected port: " + port);
            }
            this.port = port;
            return this;
        }

        @Override
        public HttpUrl.@NonNull Builder username(final @NonNull String username) {
            Objects.requireNonNull(username);
            this.encodedUsername = canonicalize(username, USERNAME_ENCODE_SET, false);
            return this;
        }

        @Override
        public HttpUrl.@NonNull Builder encodedUsername(final @NonNull String encodedUsername) {
            Objects.requireNonNull(encodedUsername);
            this.encodedUsername = canonicalize(encodedUsername, USERNAME_ENCODE_SET, true);
            return this;
        }

        @Override
        public HttpUrl.@NonNull Builder password(final @NonNull String password) {
            Objects.requireNonNull(password);
            this.encodedPassword = canonicalize(password, PASSWORD_ENCODE_SET, false);
            return this;
        }

        @Override
        public HttpUrl.@NonNull Builder encodedPassword(final @NonNull String encodedPassword) {
            Objects.requireNonNull(encodedPassword);
            this.encodedPassword = canonicalize(encodedPassword, PASSWORD_ENCODE_SET, true);
            return this;
        }

        @Override
        public HttpUrl.@NonNull Builder addPathSegment(final @NonNull String pathSegment) {
            Objects.requireNonNull(pathSegment);
            push(pathSegment, 0, pathSegment.length(), false, false);
            return this;
        }

        @Override
        public HttpUrl.@NonNull Builder addPathSegments(final @NonNull String pathSegments) {
            return addPathSegments(pathSegments, false);
        }

        @Override
        public HttpUrl.@NonNull Builder addEncodedPathSegment(final @NonNull String encodedPathSegment) {
            Objects.requireNonNull(encodedPathSegment);
            push(encodedPathSegment, 0, encodedPathSegment.length(), false, true);
            return this;
        }

        @Override
        public HttpUrl.@NonNull Builder addEncodedPathSegments(final @NonNull String encodedPathSegments) {
            return addPathSegments(encodedPathSegments, true);
        }

        private @NonNull Builder addPathSegments(final @NonNull String pathSegments, final boolean alreadyEncoded) {
            Objects.requireNonNull(pathSegments);
            var offset = 0;
            do {
                final var segmentEnd = delimiterOffset(pathSegments, "/\\", offset, pathSegments.length());
                final var addTrailingSlash = segmentEnd < pathSegments.length();
                push(pathSegments, offset, segmentEnd, addTrailingSlash, alreadyEncoded);
                offset = segmentEnd + 1;
            } while (offset <= pathSegments.length());
            return this;
        }

        @Override
        public HttpUrl.@NonNull Builder setPathSegment(final int index, final @NonNull String pathSegment) {
            Objects.requireNonNull(pathSegment);
            if (index < 0) {
                throw new IllegalArgumentException("index < 0 : " + index);
            }
            final var canonicalPathSegment = canonicalize(pathSegment, PATH_SEGMENT_ENCODE_SET, false);
            if (isDot(canonicalPathSegment) || isDotDot(canonicalPathSegment)) {
                throw new IllegalArgumentException("unexpected path segment: " + pathSegment);
            }
            encodedPathSegments.set(index, canonicalPathSegment);
            return this;
        }

        @Override
        public HttpUrl.@NonNull Builder setEncodedPathSegment(final int index,
                                                              final @NonNull String encodedPathSegment) {
            Objects.requireNonNull(encodedPathSegment);
            if (index < 0) {
                throw new IllegalArgumentException("index < 0 : " + index);
            }
            final var canonicalPathSegment = canonicalize(encodedPathSegment, PATH_SEGMENT_ENCODE_SET, true);
            if (isDot(canonicalPathSegment) || isDotDot(canonicalPathSegment)) {
                throw new IllegalArgumentException("unexpected path segment: " + encodedPathSegment);
            }
            encodedPathSegments.set(index, canonicalPathSegment);
            return this;
        }

        @Override
        public HttpUrl.@NonNull Builder removePathSegment(final int index) {
            if (index < 0) {
                throw new IllegalArgumentException("index < 0 : " + index);
            }
            encodedPathSegments.remove(index);
            if (encodedPathSegments.isEmpty()) {
                encodedPathSegments.add(""); // Always leave at least one '/'.
            }
            return this;
        }

        @Override
        public HttpUrl.@NonNull Builder encodedPath(final @NonNull String encodedPath) {
            Objects.requireNonNull(encodedPath);
            if (!encodedPath.startsWith("/")) {
                throw new IllegalArgumentException("unexpected encodedPath: " + encodedPath);
            }
            resolvePath(encodedPath, 0, encodedPath.length());
            return this;
        }

        @Override
        public HttpUrl.@NonNull Builder query(final @Nullable String query) {
            if (query == null) {
                this.encodedQueryNamesAndValues = null;
            } else {
                this.encodedQueryNamesAndValues =
                        toQueryNamesAndValues(canonicalize(
                                query,
                                QUERY_ENCODE_SET,
                                false,
                                0,
                                query.length(),
                                false,
                                true,
                                false
                        ));
            }
            return this;
        }

        @Override
        public HttpUrl.@NonNull Builder encodedQuery(final @Nullable String encodedQuery) {
            if (encodedQuery == null) {
                this.encodedQueryNamesAndValues = null;
            } else {
                this.encodedQueryNamesAndValues =
                        toQueryNamesAndValues(canonicalize(
                                encodedQuery,
                                QUERY_ENCODE_SET,
                                true,
                                0,
                                encodedQuery.length(),
                                false,
                                true,
                                false
                        ));
            }
            return this;
        }

        @Override
        public HttpUrl.@NonNull Builder addQueryParameter(final @NonNull String name, final @Nullable String value) {
            Objects.requireNonNull(name);
            if (encodedQueryNamesAndValues == null) {
                encodedQueryNamesAndValues = new ArrayList<>();
            }
            encodedQueryNamesAndValues.add(
                    canonicalize(
                            name,
                            QUERY_COMPONENT_ENCODE_SET,
                            false,
                            0,
                            name.length(),
                            false,
                            true,
                            false
                    )
            );
            if (value == null) {
                encodedQueryNamesAndValues.add(null);
            } else {
                encodedQueryNamesAndValues.add(
                        canonicalize(
                                value,
                                QUERY_COMPONENT_ENCODE_SET,
                                false,
                                0,
                                value.length(),
                                false,
                                true,
                                false
                        )
                );
            }
            return this;
        }

        @Override
        public HttpUrl.@NonNull Builder addEncodedQueryParameter(final @NonNull String encodedName,
                                                                 final @Nullable String encodedValue) {
            Objects.requireNonNull(encodedName);
            if (encodedQueryNamesAndValues == null) {
                encodedQueryNamesAndValues = new ArrayList<>();
            }
            encodedQueryNamesAndValues.add(
                    canonicalize(
                            encodedName,
                            QUERY_COMPONENT_REENCODE_SET,
                            true,
                            0,
                            encodedName.length(),
                            false,
                            true,
                            false
                    )
            );
            if (encodedValue == null) {
                encodedQueryNamesAndValues.add(null);
            } else {
                encodedQueryNamesAndValues.add(
                        canonicalize(
                                encodedValue,
                                QUERY_COMPONENT_REENCODE_SET,
                                true,
                                0,
                                encodedValue.length(),
                                false,
                                true,
                                false
                        )
                );
            }
            return this;
        }

        @Override
        public HttpUrl.@NonNull Builder setQueryParameter(final @NonNull String name, final @Nullable String value) {
            Objects.requireNonNull(name);
            removeAllQueryParameters(name);
            return addQueryParameter(name, value);
        }

        @Override
        public HttpUrl.@NonNull Builder setEncodedQueryParameter(final @NonNull String encodedName,
                                                                 final @Nullable String encodedValue) {
            Objects.requireNonNull(encodedName);
            removeAllEncodedQueryParameters(encodedName);
            return addEncodedQueryParameter(encodedName, encodedValue);
        }

        @Override
        public HttpUrl.@NonNull Builder removeAllQueryParameters(final @NonNull String name) {
            Objects.requireNonNull(name);
            if (encodedQueryNamesAndValues == null) {
                return this;
            }
            final var nameToRemove =
                    canonicalize(
                            name,
                            QUERY_COMPONENT_ENCODE_SET,
                            false,
                            0,
                            name.length(),
                            false,
                            true,
                            false
                    );
            return removeAllCanonicalQueryParameters(nameToRemove);
        }

        @Override
        public HttpUrl.@NonNull Builder removeAllEncodedQueryParameters(final @NonNull String encodedName) {
            Objects.requireNonNull(encodedName);
            if (encodedQueryNamesAndValues == null) {
                return this;
            }
            final var nameToRemove =
                    canonicalize(
                            encodedName,
                            QUERY_COMPONENT_REENCODE_SET,
                            true,
                            0,
                            encodedName.length(),
                            false,
                            true,
                            false
                    );
            return removeAllCanonicalQueryParameters(nameToRemove);
        }

        @Override
        public HttpUrl.@NonNull Builder removeAllCanonicalQueryParameters(final @NonNull String canonicalName) {
            Objects.requireNonNull(canonicalName);
            assert encodedQueryNamesAndValues != null;
            for (var i = encodedQueryNamesAndValues.size() - 2; i >= 0; i -= 2) {
                if (canonicalName.equals(encodedQueryNamesAndValues.get(i))) {
                    encodedQueryNamesAndValues.remove(i + 1);
                    encodedQueryNamesAndValues.remove(i);
                    if (encodedQueryNamesAndValues.isEmpty()) {
                        encodedQueryNamesAndValues = null;
                        return this;
                    }
                }
            }
            return this;
        }

        @Override
        public HttpUrl.@NonNull Builder fragment(final @Nullable String fragment) {
            if (fragment == null) {
                this.encodedFragment = null;
            } else {
                this.encodedFragment =
                        canonicalize(
                                fragment,
                                FRAGMENT_ENCODE_SET,
                                false,
                                0,
                                fragment.length(),
                                false,
                                false,
                                true
                        );
            }
            return this;
        }

        @Override
        public HttpUrl.@NonNull Builder encodedFragment(final @Nullable String encodedFragment) {
            if (encodedFragment == null) {
                this.encodedFragment = null;
            } else {
                this.encodedFragment =
                        canonicalize(
                                encodedFragment,
                                FRAGMENT_ENCODE_SET,
                                true,
                                0,
                                encodedFragment.length(),
                                false,
                                false,
                                true
                        );
            }
            return this;
        }

        @Override
        public @NonNull String toString() {
            final var out = new StringBuilder();
            if (scheme != null) {
                out.append(scheme);
                out.append("://");
            } else {
                out.append("//");
            }

            if (!encodedUsername.isEmpty() || !encodedPassword.isEmpty()) {
                out.append(encodedUsername);
                if (!encodedPassword.isEmpty()) {
                    out.append(':');
                    out.append(encodedPassword);
                }
                out.append('@');
            }

            if (host != null) {
                if (host.indexOf(':') >= 0) {
                    // Host is an IPv6 address.
                    out.append('[');
                    out.append(host);
                    out.append(']');
                } else {
                    out.append(host);
                }
            }

            if (port != -1 || scheme != null) {
                final var effectivePort = effectivePort();
                if (scheme == null || effectivePort != defaultPort(scheme)) {
                    out.append(':');
                    out.append(effectivePort);
                }
            }

            toPathString(encodedPathSegments, out);

            if (encodedQueryNamesAndValues != null) {
                out.append('?');
                toQueryString(encodedQueryNamesAndValues, out);
            }

            if (encodedFragment != null) {
                out.append('#');
                out.append(encodedFragment);
            }
            return out.toString();
        }

        @Override
        public @NonNull HttpUrl build() {
            if (scheme == null) {
                throw new IllegalStateException("scheme == null");
            }
            if (host == null) {
                throw new IllegalStateException("host == null");
            }
            return new RealHttpUrl(
                    scheme,
                    percentDecode(encodedUsername),
                    percentDecode(encodedPassword),
                    host,
                    effectivePort(),
                    encodedPathSegments.stream()
                            .map(UrlUtils::percentDecode)
                            .toList(),
                    (encodedQueryNamesAndValues != null)
                            ? encodedQueryNamesAndValues.stream()
                            .map(queryNamesAndValue ->
                                    (queryNamesAndValue != null)
                                            ? percentDecode(queryNamesAndValue, 0, queryNamesAndValue.length(), true)
                                            : null)
                            .toList()
                            : null,
                    (encodedFragment != null) ? percentDecode(encodedFragment) : null,
                    toString()
            );
        }

        public HttpUrl.@NonNull Builder parse(
                final @Nullable HttpUrl base,
                final @NonNull String input
        ) {
            var pos = indexOfFirstNonAsciiWhitespace(input);
            final var limit = indexOfLastNonAsciiWhitespace(input, pos);

            // Scheme.
            final var schemeDelimiterOffset = schemeDelimiterOffset(input, pos, limit);
            if (schemeDelimiterOffset != -1) {
                if (input.regionMatches(true, pos, "https:", 0, 6)) {
                    this.scheme = "https";
                    pos += 6;
                } else if (input.regionMatches(true, pos, "http:", 0, 5)) {
                    this.scheme = "http";
                    pos += 5;
                } else {
                    throw new IllegalArgumentException("Expected URL scheme 'http' or 'https' but was '" +
                            input.substring(0, schemeDelimiterOffset) + "'");
                }
            } else if (base != null) {
                this.scheme = base.getScheme();
            } else {
                final var truncated = (input.length() > 6) ? input.substring(0, 6) + "..." : input;
                throw new IllegalArgumentException(
                        "Expected URL scheme 'http' or 'https' but no scheme was found for " + truncated);
            }

            // Authority.
            var hasUsername = false;
            var hasPassword = false;
            final var slashCount = slashCount(input, pos, limit);
            if (slashCount >= 2 || base == null || !base.getScheme().equals(this.scheme)) {
                // Read an authority if either:
                //  * The input starts with 2 or more slashes. These follow the scheme if it exists.
                //  * The input scheme exists and is different from the base URL's scheme.
                //
                // The structure of an authority is:
                //   username:password@host:port
                //
                // Username, password and port are optional.
                //   [username[:password]@]host[:port]
                pos += slashCount;

                authority:
                while (true) {
                    final var componentDelimiterOffset = delimiterOffset(input, "@/\\?#", pos, limit);
                    final var c =
                            (componentDelimiterOffset != limit) ? (int) input.charAt(componentDelimiterOffset) : -1;
                    switch (c) {
                        case (int) '@' -> {
                            // User info precedes.
                            if (!hasPassword) {
                                final var passwordColonOffset =
                                        delimiterOffset(input, ':', pos, componentDelimiterOffset);
                                final var canonicalUsername =
                                        canonicalize(
                                                input,
                                                USERNAME_ENCODE_SET,
                                                true,
                                                pos,
                                                passwordColonOffset,
                                                false,
                                                false,
                                                false
                                        );
                                this.encodedUsername = (hasUsername) ? this.encodedUsername + "%40" + canonicalUsername
                                        : canonicalUsername;
                                if (passwordColonOffset != componentDelimiterOffset) {
                                    hasPassword = true;
                                    this.encodedPassword =
                                            canonicalize(
                                                    input,
                                                    PASSWORD_ENCODE_SET,
                                                    true,
                                                    passwordColonOffset + 1,
                                                    componentDelimiterOffset,
                                                    false,
                                                    false,
                                                    false
                                            );
                                }
                                hasUsername = true;
                            } else {
                                this.encodedPassword = this.encodedPassword + "%40" +
                                        canonicalize(
                                                input,
                                                PASSWORD_ENCODE_SET,
                                                true,
                                                pos,
                                                componentDelimiterOffset,
                                                false,
                                                false,
                                                false
                                        );
                            }
                            pos = componentDelimiterOffset + 1;
                        }

                        case -1, (int) '/', (int) '\\', (int) '?', (int) '#' -> {
                            // Host info precedes.
                            final var portColonOffset = portColonOffset(input, pos, componentDelimiterOffset);
                            if (portColonOffset + 1 < componentDelimiterOffset) {
                                host = toCanonicalHost(percentDecode(input, pos, portColonOffset, false));
                                port = parsePort(input, portColonOffset + 1, componentDelimiterOffset);
                                if (port == -1) {
                                    throw new IllegalArgumentException("Invalid URL port: \"" +
                                            input.substring(portColonOffset + 1, componentDelimiterOffset) + "\"");
                                }
                            } else {
                                host = toCanonicalHost(percentDecode(input, pos, portColonOffset, false));
                                port = defaultPort(scheme);
                            }
                            if (host == null) {
                                throw new IllegalArgumentException("Invalid URL host: \""
                                        + input.substring(pos, portColonOffset) + "\"");
                            }
                            pos = componentDelimiterOffset;
                            break authority;
                        }
                    }
                }
            } else {
                // This is a relative link. Copy over all authority components. Also maybe the path & query.
                this.encodedUsername = base.getEncodedUsername();
                this.encodedPassword = base.getEncodedPassword();
                this.host = base.getHost();
                this.port = base.getPort();
                this.encodedPathSegments.clear();
                this.encodedPathSegments.addAll(base.getEncodedPathSegments());
                if (pos == limit || input.charAt(pos) == '#') {
                    encodedQuery(base.getEncodedQuery());
                }
            }

            // Resolve the relative path.
            final var pathDelimiterOffset = delimiterOffset(input, "?#", pos, limit);

            resolvePath(input, pos, pathDelimiterOffset);

            pos = pathDelimiterOffset;

            // Query.
            if (pos < limit && input.charAt(pos) == '?') {
                final var queryDelimiterOffset = delimiterOffset(input, '#', pos, limit);
                this.encodedQueryNamesAndValues =
                        toQueryNamesAndValues(canonicalize(
                                input,
                                QUERY_ENCODE_SET,
                                true,
                                pos + 1,
                                queryDelimiterOffset,
                                false,
                                true,
                                false
                        ));
                pos = queryDelimiterOffset;
            }

            // Fragment.
            if (pos < limit && input.charAt(pos) == '#') {
                this.encodedFragment =
                        canonicalize(
                                input,
                                FRAGMENT_ENCODE_SET,
                                true,
                                pos + 1,
                                limit,
                                false,
                                false,
                                true
                        );
            }

            return this;
        }

        private int effectivePort() {
            if ((port != -1)) {
                return port;
            } else {
                assert scheme != null;
                return defaultPort(scheme);
            }
        }

        private void resolvePath(
                final @NonNull String input,
                final int startPos,
                final int limit
        ) {
            var pos = startPos;
            // Read a delimiter.
            if (pos == limit) {
                // Empty path: keep the base path as-is.
                return;
            }
            final var c = input.charAt(pos);
            if (c == '/' || c == '\\') {
                // Absolute path: reset to the default "/".
                encodedPathSegments.clear();
                encodedPathSegments.add("");
                pos++;
            } else {
                // Relative path: clear everything after the last '/'.
                encodedPathSegments.set(encodedPathSegments.size() - 1, "");
            }

            // Read path segments.
            var i = pos;
            while (i < limit) {
                final var pathSegmentDelimiterOffset = delimiterOffset(input, "/\\", i, limit);
                final var segmentHasTrailingSlash = pathSegmentDelimiterOffset < limit;
                push(input, i, pathSegmentDelimiterOffset, segmentHasTrailingSlash, true);
                i = pathSegmentDelimiterOffset;
                if (segmentHasTrailingSlash) i++;
            }
        }

        /**
         * Adds a path segment. If the input is ".." or equivalent, this pops a path segment.
         */
        private void push(
                final @NonNull String input,
                final int pos,
                final int limit,
                final boolean addTrailingSlash,
                final boolean alreadyEncoded
        ) {
            final var segment =
                    canonicalize(input,
                            PATH_SEGMENT_ENCODE_SET,
                            alreadyEncoded,
                            pos,
                            limit,
                            false,
                            false,
                            false
                    );
            if (isDot(segment)) {
                return; // Skip '.' path segments.
            }
            if (isDotDot(segment)) {
                pop();
                return;
            }
            if (encodedPathSegments.get(encodedPathSegments.size() - 1).isEmpty()) {
                encodedPathSegments.set(encodedPathSegments.size() - 1, segment);
            } else {
                encodedPathSegments.add(segment);
            }
            if (addTrailingSlash) {
                encodedPathSegments.add("");
            }
        }

        private void pop() {
            final var removed = encodedPathSegments.remove(encodedPathSegments.size() - 1);

            // Make sure the path ends with a '/' by either adding an empty string or clearing a segment.
            if (removed.isEmpty() && !encodedPathSegments.isEmpty()) {
                encodedPathSegments.set(encodedPathSegments.size() - 1, "");
            } else {
                encodedPathSegments.add("");
            }
        }

        /**
         * Re-encodes the components of this URL so that it satisfies (obsolete) RFC 2396, which is
         * particularly strict for certain components.
         */
        private @NonNull Builder reencodeForUri() {
            if (host != null) {
                host = host.replaceAll("[\"<>^`{|}]", "");
            }

            encodedPathSegments.replaceAll(encodedPathSegment ->
                    canonicalize(
                            encodedPathSegment,
                            PATH_SEGMENT_ENCODE_SET_URI,
                            true,
                            0,
                            encodedPathSegment.length(),
                            true,
                            false,
                            false
                    )
            );

            final var encodedQueryNamesAndValues = this.encodedQueryNamesAndValues;
            if (encodedQueryNamesAndValues != null) {
                encodedQueryNamesAndValues.replaceAll(encodedQueryNameOrValue -> {
                    if (encodedQueryNameOrValue == null) {
                        return null;
                    }
                    return canonicalize(
                            encodedQueryNameOrValue,
                            QUERY_COMPONENT_ENCODE_SET_URI,
                            true,
                            0,
                            encodedQueryNameOrValue.length(),
                            true,
                            true,
                            false
                    );
                });
            }

            if (encodedFragment != null) {
                encodedFragment = canonicalize(
                        encodedFragment,
                        FRAGMENT_ENCODE_SET_URI,
                        true,
                        0,
                        encodedFragment.length(),
                        true,
                        false,
                        true
                );
            }
            return this;
        }

        private static boolean isDot(final @NonNull String input) {
            Objects.requireNonNull(input);
            return input.equals(".") || input.equalsIgnoreCase("%2e");
        }

        private static boolean isDotDot(final @NonNull String input) {
            return input.equals("..") ||
                    input.equalsIgnoreCase("%2e.") ||
                    input.equalsIgnoreCase(".%2e") ||
                    input.equalsIgnoreCase("%2e%2e");
        }

        /**
         * Cuts this string up into alternating parameter names and values. This divides a query string
         * like `subject=math&easy&problem=5-2=3` into the list `["subject", "math", "easy", null,
         * "problem", "5-2=3"]`. Note that values may be null and may contain '=' characters.
         */
        private static @NonNull List<@Nullable String> toQueryNamesAndValues(final @NonNull String string) {
            final var result = new ArrayList<@Nullable String>();
            var pos = 0;
            while (pos <= string.length()) {
                var ampersandOffset = string.indexOf('&', pos);
                if (ampersandOffset == -1) {
                    ampersandOffset = string.length();
                }

                final var equalsOffset = string.indexOf('=', pos);
                if (equalsOffset == -1 || equalsOffset > ampersandOffset) {
                    result.add(string.substring(pos, ampersandOffset));
                    result.add(null); // No value for this name.
                } else {
                    result.add(string.substring(pos, equalsOffset));
                    result.add(string.substring(equalsOffset + 1, ampersandOffset));
                }
                pos = ampersandOffset + 1;
            }
            return result;
        }

        /**
         * @return the index of the ':' in {@code input} that is after scheme characters. Returns -1 if {@code input} does
         * not have a scheme that starts at {@code pos}.
         */
        private static int schemeDelimiterOffset(
                final @NonNull String input,
                final int pos,
                final int limit
        ) {
            Objects.requireNonNull(input);
            if (limit - pos < 2) {
                return -1;
            }

            var c = input.charAt(pos);
            if ((c < 'a' || c > 'z') && (c < 'A' || c > 'Z')) {
                return -1; // Not a scheme start char.
            }

            for (var i = pos + 1; i < limit; i++) {
                c = input.charAt(i);
                // Scheme character. Keep going.
                if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                        || c == '+' || c == '-' || c == '.') {
                    continue;
                } else if (c == ':') { // Scheme prefix!
                    return i;
                }
                return -1; // Non-scheme character before the first ':'.
            }

            return -1; // No ':'; doesn't start with a scheme.
        }

        /**
         * @return the number of '/' and '\' slashes in {@code input}, starting at {@code pos}.
         */
        private static int slashCount(
                final @NonNull String input,
                final int pos,
                final int limit
        ) {
            var slashCount = 0;
            for (var i = pos; i < limit; i++) {
                final var c = input.charAt(i);
                if (c == '\\' || c == '/') {
                    slashCount++;
                } else {
                    break;
                }
            }
            return slashCount;
        }

        /**
         * Finds the first ':' in {@code input}, skipping characters between square braces "[...]".
         */
        private static int portColonOffset(
                final @NonNull String input,
                final int pos,
                final int limit
        ) {
            var i = pos;
            while (i < limit) {
                switch (input.charAt(i)) {
                    case '[' -> {
                        while (++i < limit) {
                            if (input.charAt(i) == ']') {
                                break;
                            }
                        }
                    }
                    case ':' -> {
                        return i;
                    }
                }
                i++;
            }
            return limit; // No colon.
        }

        private static int parsePort(
                final @NonNull String input,
                final int pos,
                final int limit
        ) {
            try {
                // Canonicalize the port string to skip '\n' etc.
                final var portString = canonicalize(input, "", false, pos, limit, false,
                        false, false);
                final var i = Integer.parseInt(portString);
                return (i >= 1 && i <= 65535) ? i : -1;
            } catch (NumberFormatException _unused) {
                return -1; // Invalid port.
            }
        }
    }

    /**
     * append a path string for this list of path segments.
     */
    private static void toPathString(final @NonNull List<String> pathSegments, final @NonNull StringBuilder out) {
        pathSegments.forEach(segment -> {
            out.append('/');
            out.append(segment);
        });
    }

    /**
     * Appends this list of query names and values to a StringBuilder.
     */
    private static void toQueryString(final @NonNull List<String> queryNamesAndValues,
                                      final @NonNull StringBuilder out) {
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
