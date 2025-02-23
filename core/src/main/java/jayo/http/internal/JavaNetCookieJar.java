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

package jayo.http.internal;

import jayo.http.Cookie;
import jayo.http.CookieJar;
import jayo.http.HttpUrl;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.net.CookieHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static java.lang.System.Logger.Level.WARNING;
import static jayo.http.internal.Utils.delimiterOffset;
import static jayo.http.internal.Utils.trimSubstring;

/**
 * A cookie jar that delegates to a {@link java.net.CookieHandler}.
 */
public final class JavaNetCookieJar implements CookieJar {
    private static final System.Logger LOGGER = System.getLogger("jayo.http.JavaNetCookieJar");

    private final CookieHandler cookieHandler;

    public JavaNetCookieJar(final @NonNull CookieHandler cookieHandler) {
        assert cookieHandler != null;
        this.cookieHandler = cookieHandler;
    }

    @Override
    public void saveFromResponse(final @NonNull HttpUrl url, final @NonNull List<@NonNull Cookie> cookies) {
        Objects.requireNonNull(url);
        Objects.requireNonNull(cookies);

        final List<String> cookieStrings = new ArrayList<>();
        for (final var cookie : cookies) {
            cookieStrings.add(((RealCookie) cookie).toString(true));
        }
        final var multimap = Map.of("Set-Cookie", cookieStrings);
        try {
            cookieHandler.put(url.toUri(), multimap);
        } catch (IOException e) {
            LOGGER.log(WARNING, "Saving cookies failed for " + url.resolve("/..."), e);
        }
    }

    @Override
    public @NonNull List<@NonNull Cookie> loadForRequest(final @NonNull HttpUrl url) {
        Objects.requireNonNull(url);

        Map<String, List<String>> cookieHeaders;
        try {
            // The RI passes all headers. We don't have 'em, so we don't pass 'em!
            cookieHeaders = cookieHandler.get(url.toUri(), Map.of());
        } catch (IOException e) {
            LOGGER.log(WARNING, "Loading cookies failed for " + url.resolve("/..."), e);
            return List.of();
        }

        List<Cookie> cookies = null;
        for (final var entry : cookieHeaders.entrySet()) {
            final var key = entry.getKey();
            final var value = entry.getValue();
            if ((key.equalsIgnoreCase("Cookie") || key.equalsIgnoreCase("Cookie2")) &&
                    !value.isEmpty()) {
                for (final var header : value) {
                    if (cookies == null) {
                        cookies = new ArrayList<>();
                    }
                    cookies.addAll(decodeHeaderAsJavaNetCookies(url, header));
                }
            }
        }

        return cookies != null ? List.copyOf(cookies) : List.of();
    }

    /**
     * Convert a request header to OkHttp's cookies via {@linkplain java.net.HttpCookie HttpCookie}. That extra step
     * handles multiple cookies in a single request header, which {@link Cookie#parse} doesn't support.
     */
    private List<Cookie> decodeHeaderAsJavaNetCookies(final @NonNull HttpUrl url, final @NonNull String header) {
        assert url != null;
        assert header != null;

        final var result = new ArrayList<Cookie>();
        var pos = 0;
        var limit = header.length();
        int pairEnd;
        while (pos < limit) {
            pairEnd = delimiterOffset(header, ";,", pos, limit);
            final var equalsSign = delimiterOffset(header, '=', pos, pairEnd);
            final var name = trimSubstring(header, pos, equalsSign);
            if (name.startsWith("$")) {
                pos = pairEnd + 1;
                continue;
            }

            // We have either name=value or just a name.
            var value = (equalsSign < pairEnd) ? trimSubstring(header, equalsSign + 1, pairEnd) : "";

            // If the value is "quoted", drop the quotes.
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }

            result.add(Cookie.builder()
                    .name(name)
                    .value(value)
                    .domain(url.getHost())
                    .build());
            pos = pairEnd + 1;
        }
        return result;
    }
}
