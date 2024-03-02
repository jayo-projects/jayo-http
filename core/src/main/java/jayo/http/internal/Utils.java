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

import jayo.external.NonNegative;
import jayo.http.Headers;
import org.jspecify.annotations.NonNull;

import java.time.ZoneId;
import java.util.Objects;

public final class Utils {
    // un-instantiable
    private Utils() {
    }

    /**
     * GMT and UTC are equivalent for our purposes.
     */
    static final @NonNull ZoneId UTC = ZoneId.of("UTC");

    static final @NonNull Headers EMPTY_HEADERS = Headers.of();

    /**
     * @return the index of the first character in this string that contains a character in
     * [delimiters]. Returns endIndex if there is no such character.
     */
    static @NonNegative int delimiterOffset(
            final @NonNull String source,
            final @NonNull String delimiters,
            final @NonNegative int startIndex,
            final @NonNegative int endIndex
    ) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(delimiters);
        for (var i = startIndex; i < endIndex; i++) {
            if (delimiters.indexOf(source.charAt(i)) >= 0) {
                return i;
            }
        }
        return endIndex;
    }

    static @NonNegative int delimiterOffset(
            final @NonNull String source,
            final char delimiter,
            final @NonNegative int startIndex,
            final @NonNegative int endIndex
    ) {
        Objects.requireNonNull(source);
        for (var i = startIndex; i < endIndex; i++) {
            if (source.charAt(i) == delimiter) {
                return i;
            }
        }
        return endIndex;
    }

    public static int parseHexDigit(final char character) {
        if (character >= '0' && character <= '9') {
            return character - '0';
        }
        if (character >= 'a' && character <= 'f') {
            return character - 'a' + 10;
        }
        if (character >= 'A' && character <= 'F') {
            return character - 'A' + 10;
        }
        return -1;
    }

    static @NonNegative int indexOfFirstNonAsciiWhitespace(final @NonNull String string) {
        Objects.requireNonNull(string);
        return indexOfFirstNonAsciiWhitespace(string, 0, string.length());
    }

    /**
     * Increments [startIndex] until this string is not ASCII whitespace. Stops at [endIndex].
     */
    static @NonNegative int indexOfFirstNonAsciiWhitespace(
            final @NonNull String string,
            final @NonNegative int startIndex,
            final @NonNegative int endIndex
    ) {
        Objects.requireNonNull(string);
        for (var i = startIndex; i < endIndex; i++) {
            final var charAtIndex = string.charAt(i);
            if (charAtIndex != '\t' && charAtIndex != '\n' && charAtIndex != '\u000C' && charAtIndex != '\r'
                    && charAtIndex != ' ') {
                return i;
            }
        }
        return endIndex;
    }

    static @NonNegative int indexOfLastNonAsciiWhitespace(final @NonNull String string,
                                                          final @NonNegative int startIndex) {
        Objects.requireNonNull(string);
        return indexOfLastNonAsciiWhitespace(string, startIndex, string.length());
    }

    /**
     * Decrements [endIndex] until `input[endIndex - 1]` is not ASCII whitespace. Stops at [startIndex].
     */
    static @NonNegative int indexOfLastNonAsciiWhitespace(
            final @NonNull String string,
            final @NonNegative int startIndex,
            final @NonNegative int endIndex
    ) {
        Objects.requireNonNull(string);
        for (var i = endIndex - 1; i >= startIndex; i--) {
            final var charAtIndex = string.charAt(i);
            if (charAtIndex != '\t' && charAtIndex != '\n' && charAtIndex != '\u000C' && charAtIndex != '\r'
                    && charAtIndex != ' ') {
                return i + 1;
            }
        }
        return startIndex;
    }

    /**
     * @return true if we should void putting this header in an exception or toString().
     */
    static boolean isSensitiveHeader(final @NonNull String name) {
        Objects.requireNonNull(name);
        return name.equalsIgnoreCase("Authorization") ||
                name.equalsIgnoreCase("Cookie") ||
                name.equalsIgnoreCase("Proxy-Authorization") ||
                name.equalsIgnoreCase("Set-Cookie");
    }
}
