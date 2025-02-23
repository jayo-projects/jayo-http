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

package jayo.http;

import jayo.http.internal.RealCookie;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A <a href="https://tools.ietf.org/html/rfc6265">RFC 6265</a> Cookie.
 * <p>
 * This class doesn't support additional attributes on cookies, like
 * <a href="https://code.google.com/p/chromium/issues/detail?id=232693">Chromium's Priority=HIGH extension</a>.
 */
public sealed interface Cookie permits RealCookie {
    static @NonNull Builder builder() {
        return new RealCookie.Builder();
    }

    /**
     * Attempt to parse a {@code Set-Cookie} HTTP header value as a cookie. Returns {@code null} if {@code setCookie} is
     * not a well-formed cookie.
     */
    static @Nullable Cookie parse(final @NonNull HttpUrl url, final @NonNull String setCookie) {
        Objects.requireNonNull(url);
        Objects.requireNonNull(setCookie);

        return RealCookie.parse(System.currentTimeMillis(), url, setCookie);
    }

    /**
     * @return all the cookies from a set of HTTP response headers.
     */
    static @NonNull List<@NonNull Cookie> parseAll(final @NonNull HttpUrl url, final @NonNull Headers headers) {
        Objects.requireNonNull(url);
        Objects.requireNonNull(headers);

        return RealCookie.parseAll(System.currentTimeMillis(), url, headers);
    }

    /**
     * @return a non-empty string with this cookie's name.
     */
    @NonNull
    String getName();

    /**
     * @return a possibly empty string with this cookie's value.
     */
    @NonNull
    String getValue();

    /**
     * @return the instant that this cookie expires. This is "Fri Dec 31 9999 23:59:59.999 UTC" if the cookie is not
     * {@linkplain #isPersistent() persistent}, in which case it will expire at the end of the current session. This may
     * return a value less than the current time, in which case the cookie has already expired. Webservers may return
     * expired cookies as a mechanism to delete previously set cookies that may or may not themselves be expired.
     */
    @NonNull
    Instant getExpiresAt();

    /**
     * @return the cookie's domain. If {@link #isHostOnly()} returns {@code true} this is the only domain that matches
     * this cookie; otherwise it matches this domain and all subdomains.
     */
    @NonNull
    String getDomain();

    /**
     * @return this cookie's path. This cookie matches URLs prefixed with path segments that match this path's segments.
     * For example, if this path is {@code /foo} this cookie matches requests to {@code /foo} and {@code /foo/bar}, but
     * not {@code /} or {@code /football}.
     */
    @NonNull
    String getPath();

    /**
     * @return {@code true} if this cookie should be limited to only HTTPS requests.
     */
    boolean isSecure();

    /**
     * @return {@code true} if this cookie should be limited to only HTTP APIs. In web browsers this prevents the cookie
     * from being accessible to scripts.
     */
    boolean isHttpOnly();

    /**
     * @return {@code true} if this cookie does not expire at the end of the current session. This is {@code true} if
     * either {@code expires} or {@code max-age} is present.
     */
    boolean isPersistent();

    /**
     * @return true if this cookie's domain should be interpreted as a single host name, or false if it should be
     * interpreted as a pattern. This flag will be false if its {@code Set-Cookie} header included a {@code domain}
     * attribute.
     * <p>
     * For example, suppose the cookie's domain is {@code example.com}.
     * <ul>
     * <li>If this flag is {@code true}, it matches <b>only</b> {@code example.com}.
     * <li>If this flag is {@code false}, it matches {@code example.com} and all subdomains including
     * {@code api.example.com}, {@code www.example.com}, and {@code beta.api.example.com}.
     * </ul>
     * This is {@code true} unless the {@code domain} attribute is present.
     */
    boolean isHostOnly();

    /**
     * @return a string describing whether this cookie is sent for cross-site calls.
     * <p>
     * Two URLs are on the same site if they share a {@linkplain HttpUrl#topPrivateDomain() top private domain}.
     * Otherwise, they are cross-site URLs.
     * <p>
     * When a URL is requested, it may be in the context of another URL.
     * <ul>
     * <li><b>Embedded resources like images and iframes</b> in browsers use the context as the page in the address bar,
     * and the subject is the URL of an embedded resource.
     * <li><b>Potentially destructive navigations such as HTTP POST calls</b> use the context as the page originating
     * the navigation, and the subject is the page being navigated to.
     * </ul>
     * The values of this attribute determine whether this cookie is sent for cross-site calls:
     * <ul>
     * <li>{@code Strict}: the cookie is omitted when the subject URL is an embedded resource or a potentially
     * destructive navigation.
     * <li>{@code Lax}: the cookie is omitted when the subject URL is an embedded resource. It is sent for potentially
     * destructive navigation. This is the default value.
     * <li>{@code None}: the cookie is always sent. The {@code Secure} attribute must also be set when setting this
     * value.
     */
    @Nullable
    String getSameSite();

    /**
     * @return true if this cookie should be included on a request to {@code url}. In addition to this check, callers
     * should also confirm that this cookie has not expired.
     */
    boolean matches(final @NonNull HttpUrl url);

    @NonNull
    Builder newBuilder();

    /**
     * The builder used to create a {@link Cookie} instance.
     */
    sealed interface Builder permits RealCookie.Builder {
        @NonNull
        Builder name(final @NonNull String name);

        @NonNull
        Builder value(final @NonNull String value);

        /**
         * Sets this cookie's expiration. Any precision beyond milliseconds will be lost.
         */
        @NonNull
        Builder expiresAt(final @NonNull Instant expiresAt);

        /**
         * Sets this cookie's expiration, in the same format as {@link System#currentTimeMillis()}.
         */
        @NonNull
        Builder expiresAt(final long expiresAt);

        /**
         * Set the domain pattern for this cookie. The cookie will match {@code domain} and all of its subdomains.
         */
        @NonNull
        Builder domain(final @NonNull String domain);

        /**
         * Set the host-only domain for this cookie. The cookie will match {@code domain} but none of its subdomains.
         */
        @NonNull
        Builder hostOnlyDomain(final @NonNull String domain);

        @NonNull
        Builder path(final @NonNull String path);

        @NonNull
        Builder secure(final boolean isSecure);

        @NonNull
        Builder httpOnly(final boolean isHttpOnly);

        @NonNull
        Builder sameSite(final @NonNull String sameSite);

        @NonNull
        Cookie build();
    }
}
