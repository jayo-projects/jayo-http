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

import jayo.*;
import jayo.bytestring.ByteString;
import jayo.http.ClientResponse;
import jayo.http.JayoHttpClient;
import jayo.http.MediaType;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.Closeable;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;

public final class Utils {
    // un-instantiable
    private Utils() {
    }

    /**
     * The string "JayoHttp" unless the library has been shaded for inclusion in another library or obfuscated with
     * tools like R8 or ProGuard. In such cases it'll return a longer string like
     * "com.example.shaded.jayo.http.JayoHttp". In large applications it's possible to have multiple Jayo HTTP
     * instances; this makes it clear which is which.
     */
    public static final @NonNull String JAYO_HTTP_NAME = JayoHttpClient.class.getName()
            .replaceFirst("^jayo.http\\.", "")
            .replaceFirst("Client$", "");

    public static final @NonNull String USER_AGENT = "jayohttp/" + InternalVersion.VERSION;

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
    static int delimiterOffset(final @NonNull String string,
                               final @NonNull String delimiters,
                               final int startIndex,
                               final int endIndex) {
        assert string != null;
        assert delimiters != null;

        for (var i = startIndex; i < endIndex; i++) {
            if (delimiters.indexOf(string.charAt(i)) >= 0) {
                return i;
            }
        }
        return endIndex;
    }

    static int delimiterOffset(final @NonNull String string,
                               final char delimiter,
                               final int startIndex,
                               final int endIndex) {
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
    static int indexOfNonWhitespace(final @NonNull String string, final int startIndex) {
        assert string != null;

        for (var i = startIndex; i < string.length(); i++) {
            final var c = string.charAt(i);
            if (c != ' ' && c != '\t') {
                return i;
            }
        }
        return string.length();
    }

    static int indexOfFirstNonAsciiWhitespace(final @NonNull String string) {
        assert string != null;

        return indexOfFirstNonAsciiWhitespace(string, 0, string.length());
    }

    /**
     * Increments {@code startIndex} until this string is not ASCII whitespace. Stops at {@code endIndex}.
     */
    private static int indexOfFirstNonAsciiWhitespace(final @NonNull String string,
                                                      final int startIndex,
                                                      final int endIndex) {
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

    static int indexOfLastNonAsciiWhitespace(final @NonNull String string, final int startIndex) {
        assert string != null;

        return indexOfLastNonAsciiWhitespace(string, startIndex, string.length());
    }

    /**
     * Decrements {@code endIndex} until {@code string.chatAt(endIndex - 1)} is not ASCII whitespace. Stops at
     * {@code startIndex}.
     */
    private static int indexOfLastNonAsciiWhitespace(final @NonNull String string,
                                                     final int startIndex,
                                                     final int endIndex) {
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
     * Shortcut for {@code string.substring(startIndex, endIndex).strip()}.
     */
    static @NonNull String trimSubstring(final @NonNull String string,
                                         final int startIndex,
                                         final int endIndex) {
        assert string != null;

        return string.substring(startIndex, endIndex).strip();
//        final var start = indexOfFirstNonAsciiWhitespace(string, startIndex, endIndex);
//        final var end = indexOfLastNonAsciiWhitespace(string, start, endIndex);
//        return string.substring(start, end);
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
    public static int toNonNegativeInt(final @Nullable String string, final int defaultValue) {
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

    public static int skipAll(final @NonNull Buffer buffer, final byte b) {
        assert buffer != null;

        var count = 0;
        while (!buffer.exhausted() && buffer.getByte(0L) == b) {
            count++;
            buffer.readByte();
        }
        return count;
    }

    /**
     * Reads until {@code reader} is exhausted or the timeout has expired. This is careful to not extend the deadline if
     * one exists already.
     */
    public static boolean skipAll(final @NonNull RawReader reader, final @NonNull Duration timeout) {
        assert reader != null;
        assert timeout != null;

        try {
            // we execute the skip operation with a timeout to avoid a long wait. Jayo's Cancellable ensures that if a
            // shorter existing timeout is already configured, it will be used instead of the new one.
            return Cancellable.call(timeout, ignored -> {
                Buffer skipBuffer = Buffer.create();
                while (reader.readAtMostTo(skipBuffer, 16_709) != -1L) {
                    skipBuffer.clear();
                }
                return true; // Success! The reader has been exhausted.
            });
        } catch (JayoInterruptedIOException e) {
            return false; // We ran out of time before exhausting the reader.
        }
    }

    /**
     * Attempts to exhaust {@code reader}, returning true if successful. This is useful when reading a complete reader
     * is helpful, such as when doing so completes a cache body or frees a socket connection for reuse.
     */
    public static boolean discard(final @NonNull RawReader reader, final @NonNull Duration timeout) {
        assert reader != null;
        assert timeout != null;

        try {
            return skipAll(reader, timeout);
        } catch (JayoException e) {
            return false;
        }
    }

    public static long headersContentLength(final @NonNull ClientResponse response) {
        assert response != null;

        final var maybeContentLength = response.getHeaders().get("Content-Length");
        try {
            return (maybeContentLength != null) ? Long.parseLong(maybeContentLength) : -1L;
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    public static ClientResponse.@NonNull Builder stripBody(final @NonNull ClientResponse response) {
        assert response != null;
        return response.newBuilder()
                .body(new UnreadableResponseBody(
                        response.getBody().contentType(),
                        response.getBody().contentByteSize()));
    }

    /**
     * @return a {@link Locale#US} formatted {@link String}.
     */
    public static @NonNull String format(final @NonNull String format, final Object... args) {
        return String.format(Locale.US, format, args);
    }


    /**
     * @return an array containing all elements of the original array and then all elements of the given
     * {@code elements} array.
     */
    static <T> @NonNull T[] concat(final @NonNull T[] original, final @NonNull T[] elements) {
        assert original != null;
        assert elements != null;

        final var originalLength = original.length;
        final var elementsLength = elements.length;
        final var result = Arrays.copyOf(original, originalLength + elementsLength);
        System.arraycopy(elements, 0, result, originalLength, elementsLength);
        return result;
    }
}
