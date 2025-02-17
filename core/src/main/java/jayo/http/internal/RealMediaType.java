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

import jayo.http.MediaType;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

public final class RealMediaType implements MediaType {
    private static final @NonNull String TOKEN = "([a-zA-Z0-9-!#$%&'*+.^_`{|}~]+)";
    private static final @NonNull String QUOTED = "\"([^\"]*)\"";
    private static final @NonNull Pattern TYPE_SUBTYPE = Pattern.compile(TOKEN + "/" + TOKEN);
    private static final @NonNull Pattern PARAMETER =
            Pattern.compile(";\\s*(?:" + TOKEN + "=(?:" + TOKEN + "|" + QUOTED + "))?");

    final @NonNull String mediaType;
    private final @NonNull String type;
    private final @NonNull String subtype;
    /**
     * Alternating parameter names with their values, like {@code ["charset", "utf-8"]}.
     */
    private final @NonNull String @NonNull [] parameterNamesAndValues;

    private RealMediaType(final @NonNull String mediaType,
                         final @NonNull String type,
                         final @NonNull String subtype,
                         final @NonNull String @NonNull [] parameterNamesAndValues) {
        this.mediaType = Objects.requireNonNull(mediaType);
        this.type = Objects.requireNonNull(type);
        this.subtype = Objects.requireNonNull(subtype);
        this.parameterNamesAndValues = Objects.requireNonNull(parameterNamesAndValues);
    }

    @Override
    public @NonNull String getType() {
        return type;
    }

    @Override
    public @NonNull String getSubtype() {
        return subtype;
    }

    @Override
    public @Nullable Charset charset() {
        final var charset = parameter("charset");
        if (charset == null) {
            return null;
        }
        try {
            return Charset.forName(charset);
        } catch (IllegalArgumentException _unused) {
            return null; // This charset is invalid or unsupported. Give up.
        }
    }

    @Override
    public @NonNull Charset charset(final @NonNull Charset defaultCharset) {
        Objects.requireNonNull(defaultCharset);
        final var charset = parameter("charset");
        if (charset == null) {
            return defaultCharset;
        }
        try {
            return Charset.forName(charset);
        } catch (IllegalArgumentException _unused) {
            return defaultCharset; // This charset is invalid or unsupported. Give up.
        }
    }

    @Override
    public @Nullable String parameter(final @NonNull String name) {
        Objects.requireNonNull(name);
        for (var i = 0; i < parameterNamesAndValues.length; i += 2) {
            if (parameterNamesAndValues[i].equalsIgnoreCase(name)) {
                return parameterNamesAndValues[i + 1];
            }
        }
        return null;
    }

    @Override
    public @NonNull String toString() {
        return mediaType;
    }

    @Override
    public int hashCode() {
        return mediaType.hashCode();
    }

    @Override
    public boolean equals(final @Nullable Object other) {
        return (other instanceof RealMediaType otherAsMediaType) && mediaType.equals(otherAsMediaType.mediaType);
    }

    public static MediaType get(final @NonNull String mediaType) {
        Objects.requireNonNull(mediaType);
        final var typeSubtype = matchAt(TYPE_SUBTYPE, mediaType, 0);
        if (typeSubtype == null) {
            throw new IllegalArgumentException("No subtype found for: \"" + mediaType + "\"");
        }
        final var type = typeSubtype.group(1).toLowerCase(Locale.ROOT);
        final var subtype = typeSubtype.group(2).toLowerCase(Locale.ROOT);

        final var parameterNamesAndValues = new ArrayList<String>();
        var s = typeSubtype.end();
        while (s < mediaType.length()) {
            final var parameter = matchAt(PARAMETER, mediaType, s);
            if (parameter == null) {
                throw new IllegalArgumentException(
                        "Parameter is not formatted correctly: \"" + mediaType.substring(s) + "\" for: \"" + mediaType +
                                "\"");
            }

            final var name = parameter.group(1);
            if (name == null) {
                s = parameter.end();
                continue;
            }

            final var token = parameter.group(2);
            final String value;
            if (token == null) {
                // Value is "double-quoted". That's valid and our regex group already strips the quotes.
                value = parameter.group(3);
            } else if (token.startsWith("'") && token.endsWith("'") && token.length() > 2) {
                // If the token is 'single-quoted' it's invalid! But we're lenient and strip the quotes.
                value = token.substring(1, token.length() - 1);
            } else {
                value = token;
            }

            parameterNamesAndValues.add(name);
            parameterNamesAndValues.add(value);
            s = parameter.end();
        }

        return new RealMediaType(mediaType, type, subtype, parameterNamesAndValues.toArray(String[]::new));
    }

    private static @Nullable MatchResult matchAt(final @NonNull Pattern pattern,
                                                 final @NonNull String input,
                                                 final int index) {
        final var matcher = pattern.matcher(input)
                .useAnchoringBounds(false)
                .useTransparentBounds(true)
                .region(index, input.length());
        if (matcher.lookingAt()) {
            return matcher;
        }
        return null;
    }
}
