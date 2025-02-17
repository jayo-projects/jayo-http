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

package jayo.http.internal.url;

import jayo.Buffer;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static jayo.http.internal.Utils.parseHexDigit;

public final class UrlUtils {
    // un-instantiable
    private UrlUtils() {
    }

    private final static char[] HEX_DIGITS =
            new char[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'};
    public final static String USERNAME_ENCODE_SET = " \"':;<=>@[]^`{}|/\\?#";
    public final static String PASSWORD_ENCODE_SET = " \"':;<=>@[]^`{}|/\\?#";
    public final static String PATH_SEGMENT_ENCODE_SET = " \"<>^`{}|/\\?#";
    public final static String PATH_SEGMENT_ENCODE_SET_URI = "[]";
    public final static String QUERY_ENCODE_SET = " \"'<>#";
    public final static String QUERY_COMPONENT_REENCODE_SET = " \"'<>#&=";
    public final static String QUERY_COMPONENT_ENCODE_SET = " !\"#$&'(),/:;<=>?@[]\\^`{|}~";
    public final static String QUERY_COMPONENT_ENCODE_SET_URI = "\\^`{|}";
    public final static String FORM_ENCODE_SET = " !\"#$&'()+,/:;<=>?@[\\]^`{|}~";
    public final static String FRAGMENT_ENCODE_SET = "";
    public final static String FRAGMENT_ENCODE_SET_URI = " \"#<>\\^`{|}";

    /**
     * @return a substring of `input` on the range `[pos..limit)` with the following
     * transformations:
     * <p>
     * * Tabs, newlines, form feeds and carriage returns are skipped.
     * <p>
     * * In queries, ' ' is encoded to '+' and '+' is encoded to "%2B".
     * <p>
     * * Characters in `encodeSet` are percent-encoded.
     * <p>
     * * Control characters and non-ASCII characters are percent-encoded.
     * <p>
     * * All other characters are copied without transformation.
     *
     * @param alreadyEncoded true to leave '%' as-is; false to convert it to '%25'.
     * @param strict         true to encode '%' if it is not the prefix of a valid percent encoding.
     * @param plusIsSpace    true to encode '+' as "%2B" if it is not already encoded.
     * @param unicodeAllowed true to leave non-ASCII codepoint unencoded.
     * @param charset        which charset to use, null equals UTF-8.
     */
    public static @NonNull String canonicalizeWithCharset(
            final @NonNull String input,
            final @NonNull String encodeSet,
            final boolean alreadyEncoded,
            final int pos,
            final int limit,
            final boolean strict,
            final boolean plusIsSpace,
            final boolean unicodeAllowed,
            final @Nullable Charset charset
    ) {
        int codePoint;
        var i = pos;
        while (i < limit) {
            codePoint = input.codePointAt(i);
            if (codePoint < 0x20 ||
                    codePoint == 0x7f ||
                    codePoint >= 0x80 && !unicodeAllowed ||
                    (encodeSet.indexOf(codePoint) >= 0) ||
                    codePoint == ((int) '%') &&
                            (!alreadyEncoded || strict && !isPercentEncoded(input, i, limit)) ||
                    codePoint == ((int) '+') && plusIsSpace
            ) {
                // Slow path: the character at i requires encoding!
                final var out = Buffer.create();
                out.write(input, pos, i);
                writeCanonicalized(
                        out,
                        input,
                        encodeSet,
                        alreadyEncoded,
                        i,
                        limit,
                        strict,
                        plusIsSpace,
                        unicodeAllowed,
                        charset
                );
                return out.readString();
            }
            i += Character.charCount(codePoint);
        }

        // Fast path: no characters in [pos..limit) required encoding.
        return input.substring(pos, limit);
    }

    private static void writeCanonicalized(
            final @NonNull Buffer buffer,
            final @NonNull String input,
            final @NonNull String encodeSet,
            final boolean alreadyEncoded,
            final int pos,
            final int limit,
            final boolean strict,
            final boolean plusIsSpace,
            final boolean unicodeAllowed,
            final @Nullable Charset charset
    ) {
        Buffer encodedCharBuffer = null; // Lazily allocated.
        int codePoint;
        var i = pos;
        while (i < limit) {
            codePoint = input.codePointAt(i);
            if (alreadyEncoded && (
                    codePoint == ((int) '\t') || codePoint == ((int) '\n') ||
                            codePoint == ((int) '\u000c') || codePoint == ((int) '\r')
            )) {
                // Skip this character.
            } else //noinspection StringEquality
                if (codePoint == ((int) ' ') && encodeSet == FORM_ENCODE_SET) {
                    // Encode ' ' as '+'.
                    buffer.write("+");
                } else if (codePoint == ((int) '+') && plusIsSpace) {
                    // Encode '+' as '%2B' since we permit ' ' to be encoded as either '+' or '%20'.
                    buffer.write(alreadyEncoded ? "+" : "%2B");
                } else if (codePoint < 0x20 ||
                        codePoint == 0x7f ||
                        codePoint >= 0x80 && !unicodeAllowed ||
                        (encodeSet.indexOf(codePoint) >= 0) ||
                        codePoint == ((int) '%') &&
                                (!alreadyEncoded || strict && !isPercentEncoded(input, i, limit))
                ) {
                    // Percent encode this character.
                    if (encodedCharBuffer == null) {
                        encodedCharBuffer = Buffer.create();
                    }

                    if (charset == null || charset.equals(StandardCharsets.UTF_8)) {
                        encodedCharBuffer.writeUtf8CodePoint(codePoint);
                    } else {
                        encodedCharBuffer.write(input, i, i + Character.charCount(codePoint), charset);
                    }

                    while (!encodedCharBuffer.exhausted()) {
                        final var b = ((int) encodedCharBuffer.readByte()) & 0xff;
                        buffer.writeByte((byte) ((int) '%'));
                        buffer.writeByte((byte) ((int) HEX_DIGITS[b >> 4 & 0xf]));
                        buffer.writeByte((byte) ((int) HEX_DIGITS[b & 0xf]));
                    }
                } else {
                    // This character doesn't need encoding. Just copy it over.
                    buffer.writeUtf8CodePoint(codePoint);
                }
            i += Character.charCount(codePoint);
        }
    }

    private static void writePercentDecoded(
            final @NonNull Buffer buffer,
            final @NonNull String encoded,
            final int pos,
            final int limit,
            final boolean plusIsSpace
    ) {
        int codePoint;
        var i = pos;
        while (i < limit) {
            codePoint = encoded.codePointAt(i);
            if (codePoint == (int) '%' && i + 2 < limit) {
                final var d1 = parseHexDigit(encoded.charAt(i + 1));
                final var d2 = parseHexDigit(encoded.charAt(i + 2));
                if (d1 != -1 && d2 != -1) {
                    buffer.writeByte((byte) ((d1 << 4) + d2));
                    i += 2;
                    i += Character.charCount(codePoint);
                    continue;
                }
            } else if (codePoint == (int) '+' && plusIsSpace) {
                buffer.writeByte((byte) ' ');
                i++;
                continue;
            }
            buffer.writeUtf8CodePoint(codePoint);
            i += Character.charCount(codePoint);
        }
    }

    public static @NonNull String canonicalize(
            final @NonNull String input,
            final @NonNull String encodeSet,
            final boolean alreadyEncoded
    ) {
        return canonicalize(input, encodeSet, alreadyEncoded, 0, input.length(), false, false, false);
    }

    public static @NonNull String canonicalize(
            final @NonNull String input,
            final @NonNull String encodeSet,
            final boolean alreadyEncoded,
            final int pos,
            final int limit,
            final boolean strict,
            final boolean plusIsSpace,
            final boolean unicodeAllowed
    ) {
        return canonicalizeWithCharset(
                input,
                encodeSet,
                alreadyEncoded,
                pos,
                limit,
                strict,
                plusIsSpace,
                unicodeAllowed,
                null
        );
    }

    public static @NonNull String percentDecode(final @NonNull String encoded) {
        return percentDecode(encoded, 0, encoded.length(), false);
    }

    public static @NonNull String percentDecode(
            final @NonNull String encoded,
            final int pos,
            final int limit,
            final boolean plusIsSpace
    ) {
        Objects.requireNonNull(encoded);
        for (var i = pos; i < limit; i++) {
            final var c = encoded.charAt(i);
            if (c == '%' || c == '+' && plusIsSpace) {
                // Slow path: the character at i requires decoding!
                final var out = Buffer.create();
                out.write(encoded, pos, i);
                writePercentDecoded(out, encoded, i, limit, plusIsSpace);
                return out.readString();
            }
        }

        // Fast path: no characters in [pos..limit) required decoding.
        return encoded.substring(pos, limit);
    }

    private static boolean isPercentEncoded(
            final @NonNull String input,
            final int pos,
            final int limit
    ) {
        return pos + 2 < limit &&
                input.charAt(pos) == '%' &&
                parseHexDigit(input.charAt(pos + 1)) != -1 &&
                parseHexDigit(input.charAt(pos + 2)) != -1;
    }
}
