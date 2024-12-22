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

import jayo.http.Challenge;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.util.Collections.singletonMap;
import static java.util.Collections.unmodifiableMap;

public final class RealChallenge implements Challenge {
    private final @NonNull String scheme;
    private final @NonNull Map<@Nullable String, @NonNull String> authParams;

    public RealChallenge(final @NonNull String scheme,
                         final @NonNull Map<@Nullable String, @NonNull String> authParams) {
        this.scheme = Objects.requireNonNull(scheme);
        Objects.requireNonNull(authParams);
        final var newAuthParams = new HashMap<String, String>();
        authParams.forEach((key, value) -> {
            final var newKey = (key != null) ? key.toLowerCase(Locale.US) : null;
            newAuthParams.put(newKey, value);
        });
        this.authParams = unmodifiableMap(newAuthParams);
    }

    /**
     * for tests
     */
    RealChallenge(final @NonNull String scheme, final @NonNull String realm) {
        this(scheme, singletonMap("realm", realm));
    }

    @Override
    public @NonNull String getScheme() {
        return scheme;
    }

    @Override
    public @NonNull Map<@Nullable String, @NonNull String> getAuthParams() {
        return authParams;
    }

    @Override
    public @Nullable String getRealm() {
        return authParams.get("realm");
    }

    @Override
    public @NonNull Charset getCharset() {
        final var charset = authParams.get("charset");
        if (charset != null) {
            try {
                return Charset.forName(charset);
            } catch (Exception ignore) {
            }
        }
        return ISO_8859_1;
    }

    @Override
    public @NonNull Challenge withCharset(@NonNull Charset charset) {
        final var authParams = new HashMap<>(this.authParams);
        authParams.put("charset", charset.name());
        return new RealChallenge(scheme, authParams);
    }

    @Override
    public boolean equals(final @Nullable Object other) {
        if (!(other instanceof RealChallenge that)) return false;

        return scheme.equals(that.scheme) && authParams.equals(that.authParams);
    }

    @Override
    public int hashCode() {
        int result = scheme.hashCode();
        result = 31 * result + authParams.hashCode();
        return result;
    }

    @Override
    public @NonNull String toString() {
        return scheme + "authParams=" + authParams;
    }
}
