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

import jayo.http.CacheControl;
import jayo.http.Headers;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

import static jayo.http.internal.Utils.indexOfNonWhitespace;
import static jayo.http.internal.Utils.toNonNegativeInt;

public final class RealCacheControl implements CacheControl {
    private final boolean noCache;
    private final boolean noStore;
    private final int maxAgeSeconds;
    private final int sMaxAgeSeconds;
    private final boolean isPrivate;
    private final boolean isPublic;
    private final boolean mustRevalidate;
    private final int maxStaleSeconds;
    private final int minFreshSeconds;
    private final boolean onlyIfCached;
    private final boolean noTransform;
    private final boolean immutable;
    private @Nullable String headerValue;

    private RealCacheControl(
            final boolean noCache, final boolean noStore, final int maxAgeSeconds, final int sMaxAgeSeconds,
            final boolean isPrivate, final boolean isPublic, final boolean mustRevalidate,
            final int maxStaleSeconds, final int minFreshSeconds, final boolean onlyIfCached,
            final boolean noTransform, final boolean immutable, final @Nullable String headerValue) {
        this.noCache = noCache;
        this.noStore = noStore;
        this.maxAgeSeconds = maxAgeSeconds;
        this.sMaxAgeSeconds = sMaxAgeSeconds;
        this.isPrivate = isPrivate;
        this.isPublic = isPublic;
        this.mustRevalidate = mustRevalidate;
        this.maxStaleSeconds = maxStaleSeconds;
        this.minFreshSeconds = minFreshSeconds;
        this.onlyIfCached = onlyIfCached;
        this.noTransform = noTransform;
        this.immutable = immutable;
        this.headerValue = headerValue;
    }

    @Override
    public boolean noCache() {
        return noCache;
    }

    @Override
    public boolean noStore() {
        return noStore;
    }

    @Override
    public int maxAgeSeconds() {
        return maxAgeSeconds;
    }

    @Override
    public int sMaxAgeSeconds() {
        return sMaxAgeSeconds;
    }

    @Override
    public boolean isPrivate() {
        return isPrivate;
    }

    @Override
    public boolean isPublic() {
        return isPublic;
    }

    @Override
    public boolean mustRevalidate() {
        return mustRevalidate;
    }

    @Override
    public int maxStaleSeconds() {
        return maxStaleSeconds;
    }

    @Override
    public int minFreshSeconds() {
        return minFreshSeconds;
    }

    @Override
    public boolean onlyIfCached() {
        return onlyIfCached;
    }

    @Override
    public boolean noTransform() {
        return noTransform;
    }

    @Override
    public boolean immutable() {
        return immutable;
    }

    @Override
    public @NonNull String toString() {
        var result = headerValue;
        if (result == null) {
            final var sb = new StringBuilder();
            if (noCache) {
                sb.append("no-cache, ");
            }
            if (noStore) {
                sb.append("no-store, ");
            }
            if (maxAgeSeconds != -1) {
                sb.append("max-age=").append(maxAgeSeconds).append(", ");
            }
            if (sMaxAgeSeconds != -1) {
                sb.append("s-maxage=").append(sMaxAgeSeconds).append(", ");
            }
            if (isPrivate) {
                sb.append("private, ");
            }
            if (isPublic) {
                sb.append("public, ");
            }
            if (mustRevalidate) {
                sb.append("must-revalidate, ");
            }
            if (maxStaleSeconds != -1) {
                sb.append("max-stale=").append(maxStaleSeconds).append(", ");
            }
            if (minFreshSeconds != -1) {
                sb.append("min-fresh=").append(minFreshSeconds).append(", ");
            }
            if (onlyIfCached) {
                sb.append("only-if-cached, ");
            }
            if (noTransform) {
                sb.append("no-transform, ");
            }
            if (immutable) {
                sb.append("immutable, ");
            }
            if (sb.isEmpty()) {
                return "";
            }
            sb.delete(sb.length() - 2, sb.length());
            result = sb.toString();
            headerValue = result;
        }
        return result;
    }

    public static @NonNull CacheControl parse(final @NonNull Headers headers) {
        Objects.requireNonNull(headers);
        var noCache = false;
        var noStore = false;
        var maxAgeSeconds = -1;
        var sMaxAgeSeconds = -1;
        var isPrivate = false;
        var isPublic = false;
        var mustRevalidate = false;
        var maxStaleSeconds = -1;
        var minFreshSeconds = -1;
        var onlyIfCached = false;
        var noTransform = false;
        var immutable = false;

        var canUseHeaderValue = true;
        String headerValue = null;

        for (var i = 0; i < headers.size(); i++) {
            final var name = headers.name(i);
            final var value = headers.value(i);

            if (name.equalsIgnoreCase("Cache-Control")) {
                if (headerValue != null) {
                    // Multiple cache-control headers means we can't use the raw value.
                    canUseHeaderValue = false;
                } else {
                    headerValue = value;
                }
            } else if (name.equalsIgnoreCase("Pragma")) {
                // Might specify additional cache-control params. We invalidate just in case.
                canUseHeaderValue = false;
            } else {
                continue;
            }

            var pos = 0;
            while (pos < value.length()) {
                final var tokenStart = pos;
                pos = indexOfElement(value, "=,;", pos);
                final var directive = value.substring(tokenStart, pos).strip().toLowerCase(Locale.US);
                final String parameter;

                if (pos == value.length() || value.charAt(pos) == ',' || value.charAt(pos) == ';') {
                    pos++; // Consume ',' or ';' (if necessary).
                    parameter = null;
                } else {
                    pos++; // Consume '='.
                    pos = indexOfNonWhitespace(value, pos);

                    if (pos < value.length() && value.charAt(pos) == '\"') {
                        // Quoted string.
                        pos++; // Consume '"' open quote.
                        final var parameterStart = pos;
                        pos = value.indexOf('"', pos);
                        parameter = value.substring(parameterStart, pos);
                        pos++; // Consume '"' close quote (if necessary).
                    } else {
                        // Unquoted string.
                        final var parameterStart = pos;
                        pos = indexOfElement(value, ",;", pos);
                        parameter = value.substring(parameterStart, pos).strip();
                    }
                }

                switch (directive) {
                    case "no-cache" -> noCache = true;
                    case "no-store" -> noStore = true;
                    case "max-age" -> maxAgeSeconds = toNonNegativeInt(parameter, -1);
                    case "s-maxage" -> sMaxAgeSeconds = toNonNegativeInt(parameter, -1);
                    case "private" -> isPrivate = true;
                    case "public" -> isPublic = true;
                    case "must-revalidate" -> mustRevalidate = true;
                    case "max-stale" -> maxStaleSeconds = toNonNegativeInt(parameter, Integer.MAX_VALUE);
                    case "min-fresh" -> minFreshSeconds = toNonNegativeInt(parameter, -1);
                    case "only-if-cached" -> onlyIfCached = true;
                    case "no-transform" -> noTransform = true;
                    case "immutable" -> immutable = true;
                }
            }
        }

        if (!canUseHeaderValue) {
            headerValue = null;
        }

        return new RealCacheControl(
                noCache,
                noStore,
                maxAgeSeconds,
                sMaxAgeSeconds,
                isPrivate,
                isPublic,
                mustRevalidate,
                maxStaleSeconds,
                minFreshSeconds,
                onlyIfCached,
                noTransform,
                immutable,
                headerValue
        );
    }

    /**
     * @return the next index in this at or after {@code startIndex} that is a character from {@code characters}.
     * Returns the input length if none of the requested characters can be found.
     */
    private static int indexOfElement(
            final @NonNull String string,
            final @NonNull String characters,
            final int startIndex
            ) {
        for (var i = startIndex; i < string.length(); i++) {
            if (characters.indexOf(string.charAt(i)) >= 0) {
                return i;
            }
        }
        return string.length();
    }

    public static final class Builder implements CacheControl.Builder {
        private boolean noCache = false;
        private boolean noStore = false;
        private int maxAgeSeconds = -1;
        private int maxStaleSeconds = -1;
        private int minFreshSeconds = -1;
        private boolean onlyIfCached = false;
        private boolean noTransform = false;
        private boolean immutable = false;

        @Override
        public CacheControl.@NonNull Builder noCache() {
            noCache = true;
            return this;
        }

        @Override
        public CacheControl.@NonNull Builder noStore() {
            noStore = true;
            return this;
        }

        @Override
        public CacheControl.@NonNull Builder onlyIfCached() {
            onlyIfCached = true;
            return this;
        }

        @Override
        public CacheControl.@NonNull Builder noTransform() {
            noTransform = true;
            return this;
        }

        @Override
        public CacheControl.@NonNull Builder immutable() {
            immutable = true;
            return this;
        }

        @Override
        public CacheControl.@NonNull Builder maxAge(final @NonNull Duration maxAge) {
            Objects.requireNonNull(maxAge);
            final var maxAgeSeconds = maxAge.toSeconds();
            if (maxAgeSeconds < 0) {
                throw new IllegalArgumentException("maxAge < 0: " + maxAgeSeconds);
            }
            this.maxAgeSeconds = clampToInt(maxAgeSeconds);
            return this;
        }

        @Override
        public CacheControl.@NonNull Builder maxStale(final @NonNull Duration maxStale) {
            Objects.requireNonNull(maxStale);
            final var maxStaleSeconds = maxStale.toSeconds();
            if (maxStaleSeconds < 0) {
                throw new IllegalArgumentException("maxStale < 0: " + maxStaleSeconds);
            }
            this.maxStaleSeconds = clampToInt(maxStaleSeconds);
            return this;
        }

        @Override
        public CacheControl.@NonNull Builder minFresh(final @NonNull Duration minFresh) {
            Objects.requireNonNull(minFresh);
            final var minFreshSeconds = minFresh.toSeconds();
            if (minFreshSeconds < 0) {
                throw new IllegalArgumentException("minFresh < 0: " + minFreshSeconds);
            }
            this.minFreshSeconds = clampToInt(minFreshSeconds);
            return this;
        }

        @Override
        public @NonNull CacheControl build() {
            return new RealCacheControl(
                    noCache,
                    noStore,
                    maxAgeSeconds,
                    -1,
                    false,
                    false,
                    false,
                    maxStaleSeconds,
                    minFreshSeconds,
                    onlyIfCached,
                    noTransform,
                    immutable,
                    null
            );
        }

        private static int clampToInt(final long seconds) {
            return (seconds > Integer.MAX_VALUE) ? Integer.MAX_VALUE : (int) seconds;
        }
    }
}
