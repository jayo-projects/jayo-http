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

import jayo.http.internal.JavaNetCookieJar;
import org.jspecify.annotations.NonNull;

import java.net.CookieHandler;
import java.util.List;
import java.util.Objects;

/**
 * Provides <b>policy</b> and <b>persistence</b> for HTTP cookies.
 * <p>
 * As policy, implementations of this interface are responsible for selecting which cookies to accept and which to
 * reject. A reasonable policy is to reject all cookies, though that may interfere with session-based authentication
 * schemes that require cookies.
 * <p>
 * As persistence, implementations of this interface must also provide storage of cookies. Simple implementations may
 * store cookies in memory; sophisticated ones may use the file system or database to hold accepted cookies. The
 * <a href="https://tools.ietf.org/html/rfc6265#section-5.3">cookie storage model</a> specifies policies for updating
 * and expiring cookies.
 */
public interface CookieJar {
    /**
     * A cookie jar that never accepts any cookies.
     */
    @NonNull
    CookieJar NO_COOKIES = new NoCookies();

    /**
     * A cookie jar that delegates to a {@link java.net.CookieHandler}.
     */
    static @NonNull CookieJar javaNetCookieJar(final @NonNull CookieHandler cookieHandler) {
        Objects.requireNonNull(cookieHandler);
        return new JavaNetCookieJar(cookieHandler);
    }

    /**
     * Saves {@code cookies} from an HTTP response to this store according to this jar's policy.
     * <p>
     * Note that this method may be called a second time for a single HTTP response if the response includes a trailer.
     * For this obscure HTTP feature, {@code cookies} contains only the trailer's cookies.
     */
    void saveFromResponse(final @NonNull HttpUrl url, final @NonNull List<@NonNull Cookie> cookies);

    /**
     * Load cookies from this jar for an HTTP request to {@code url}. This method returns a possibly empty list of
     * cookies for the network request.
     * <p>
     * Simple implementations will return the accepted cookies that have not yet expired and that
     * {@linkplain Cookie#matches(HttpUrl) match} {@code url}.
     */
    @NonNull
    List<@NonNull Cookie> loadForRequest(final @NonNull HttpUrl url);

    final class NoCookies implements CookieJar {
        @Override
        public void saveFromResponse(final @NonNull HttpUrl url, final @NonNull List<@NonNull Cookie> cookies) {
            Objects.requireNonNull(url);
            Objects.requireNonNull(cookies);
        }

        @Override
        public @NonNull List<@NonNull Cookie> loadForRequest(final @NonNull HttpUrl url) {
            Objects.requireNonNull(url);
            return List.of();
        }
    }
}
