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

import jayo.ByteString;
import jayo.JayoException;
import jayo.Options;
import jayo.Reader;
import jayo.external.NonNegative;
import jayo.http.Headers;
import jayo.http.MediaType;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.Closeable;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;

public final class Utils {
    // un-instantiable
    private Utils() {
    }

    static final @NonNull Headers EMPTY_HEADERS = Headers.of();

    /**
     * Byte order marks.
     */
    private static final @NonNull Options UNICODE_BOMS =
            Options.of(
                    // UTF-8.
                    ByteString.decodeHex("efbbbf"),
                    // UTF-16BE.
                    ByteString.decodeHex("feff"),
                    // UTF-32LE.
                    ByteString.decodeHex("fffe0000"),
                    // UTF-16LE.
                    ByteString.decodeHex("fffe"),
                    // UTF-32BE.
                    ByteString.decodeHex("0000feff")
            );

    /**
     * @return the index of the first character in this string that contains a character in {@code delimiters}.
     * Returns endIndex if there is no such character.
     */
    static @NonNegative int delimiterOffset(
            final @NonNull String string,
            final @NonNull String delimiters,
            final @NonNegative int startIndex,
            final @NonNegative int endIndex
    ) {
        assert string != null;
        assert delimiters != null;

        for (var i = startIndex; i < endIndex; i++) {
            if (delimiters.indexOf(string.charAt(i)) >= 0) {
                return i;
            }
        }
        return endIndex;
    }

    static @NonNegative int delimiterOffset(
            final @NonNull String string,
            final char delimiter,
            final @NonNegative int startIndex,
            final @NonNegative int endIndex
    ) {
        assert string != null;

        for (var i = startIndex; i < endIndex; i++) {
            if (string.charAt(i) == delimiter) {
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

    /**
     * @return the index of the next non-whitespace character in this. Result is undefined if input
     * contains newline characters.
     */
    static @NonNegative int indexOfNonWhitespace(final @NonNull String string, final @NonNegative int startIndex) {
        assert string != null;

        for (var i = startIndex; i < string.length(); i++) {
            final var c = string.charAt(i);
            if (c != ' ' && c != '\t') {
                return i;
            }
        }
        return string.length();
    }

    static @NonNegative int indexOfFirstNonAsciiWhitespace(final @NonNull String string) {
        assert string != null;

        return indexOfFirstNonAsciiWhitespace(string, 0, string.length());
    }

    /**
     * Increments {@code startIndex} until this string is not ASCII whitespace. Stops at {@code endIndex}.
     */
    static @NonNegative int indexOfFirstNonAsciiWhitespace(
            final @NonNull String string,
            final @NonNegative int startIndex,
            final @NonNegative int endIndex
    ) {
        assert string != null;

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
        assert string != null;

        return indexOfLastNonAsciiWhitespace(string, startIndex, string.length());
    }

    /**
     * Decrements {@code endIndex} until {@code string.chatAt(endIndex - 1)} is not ASCII whitespace. Stops at
     * {@code startIndex}.
     */
    static @NonNegative int indexOfLastNonAsciiWhitespace(
            final @NonNull String string,
            final @NonNegative int startIndex,
            final @NonNegative int endIndex
    ) {
        assert string != null;

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
        assert name != null;

        return name.equalsIgnoreCase("Authorization") ||
                name.equalsIgnoreCase("Cookie") ||
                name.equalsIgnoreCase("Proxy-Authorization") ||
                name.equalsIgnoreCase("Set-Cookie");
    }

    static @NonNull Charset charsetOrUtf8(final @Nullable MediaType contentType) {
        if (contentType == null || contentType.charset() == null) {
            return StandardCharsets.UTF_8;
        }

        //noinspection DataFlowIssue
        return contentType.charset();
    }

    record CharsetMediaType(@NonNull Charset charset, @Nullable MediaType contentType) {
    }

    static @NonNull CharsetMediaType chooseCharset(final @Nullable MediaType contentType) {
        var charset = StandardCharsets.UTF_8;
        var finalContentType = contentType;
        if (contentType != null) {
            final var resolvedCharset = contentType.charset();
            if (resolvedCharset == null) {
                finalContentType = MediaType.parse(contentType + "; charset=utf-8");
            } else {
                charset = resolvedCharset;
            }
        }
        return new CharsetMediaType(charset, finalContentType);
    }

    static @NonNull Charset readBomAsCharset(final @NonNull Reader reader, final @NonNull Charset defaultCharset) {
        return switch (reader.select(UNICODE_BOMS)) {
            // a mapping from the index of encoding methods in UNICODE_BOMS to its corresponding encoding method
            case 0 -> StandardCharsets.UTF_8;
            case 1 -> StandardCharsets.UTF_16BE;
            case 2 -> Charset.forName("UTF-32LE");
            case 3 -> StandardCharsets.UTF_16LE;
            case 4 -> Charset.forName("UTF-32BE");
            case -1 -> defaultCharset;
            default -> throw new AssertionError();
        };
    }

    /**
     * @return this as a non-negative integer, or {@code 0} if it is negative, or {@code Integer.MAX_VALUE} if it is too
     * large, or {@code defaultValue} if it cannot be parsed.
     */
    static @NonNegative int toNonNegativeInt(final @Nullable String string, final @NonNegative int defaultValue) {
        if (string == null) {
            return defaultValue;
        }
        try {
            final var value = Long.parseLong(string);
            if (value > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            } else if (value < 0) {
                return 0;
            }
            return (int) value;
        } catch (NumberFormatException _unused) {
            return defaultValue;
        }
    }

    static boolean startsWithIgnoreCase(final @NonNull String string, final @NonNull String prefix) {
        assert string != null;
        assert prefix != null;

        return string.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    /**
     * @return an array containing only elements found in this array and also in [other]. The returned
     * elements are in the same order as in this.
     */
    static @NonNull String @NonNull [] intersect(final @NonNull String @NonNull [] first,
                                                 final @NonNull String @NonNull [] second,
                                                 final @NonNull Comparator<? super String> comparator) {
        assert first != null;
        assert second != null;
        assert comparator != null;

        final var result = new ArrayList<String>();
        for (final var a : first) {
            for (final var b : second) {
                if (comparator.compare(a, b) == 0) {
                    result.add(a);
                    break;
                }
            }
        }
        return result.toArray(String[]::new);
    }

    /**
     * @return true if there is an element in the first array that is also in the second. This method terminates if any
     * intersection is found. The sizes of both arguments are assumed to be so small, and the likelihood of an
     * intersection so great, that it is not worth the CPU cost of sorting or the memory cost of hashing.
     */
    static boolean hasIntersection(final @NonNull String @NonNull [] first,
                                   final @NonNull String @Nullable [] second,
                                   final @NonNull Comparator<? super String> comparator) {
        assert first != null;
        assert comparator != null;

        if (first.length == 0 || second == null || second.length == 0) {
            return false;
        }
        for (final var a : first) {
            for (final var b : second) {
                if (comparator.compare(a, b) == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Closes this {@code closeable}, ignoring any checked exceptions and any {@link JayoException}.
     */
    public static void closeQuietly(final @NonNull Closeable closeable) {
        assert closeable != null;

        try {
            closeable.close();
        } catch (JayoException ignored) {
        } catch (RuntimeException rethrown) {
            throw rethrown;
        } catch (Exception ignored) {
        }
    }
}
