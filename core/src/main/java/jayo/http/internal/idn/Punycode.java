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

package jayo.http.internal.idn;

import jayo.Buffer;
import jayo.ByteString;
import jayo.Utf8;
import jayo.external.NonNegative;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class Punycode {
    // un-instantiable
    private Punycode() {
    }

    public final static String PREFIX_STRING = "xn--";
    public final static ByteString PREFIX = Utf8.encodeUtf8(PREFIX_STRING);

    private final static int BASE = 36;
    private final static int TMIN = 1;
    private final static int TMAX = 26;
    private final static int SKEW = 38;
    private final static int DAMP = 700;
    private final static int INITIAL_BIAS = 72;
    private final static int INITIAL_N = 0x80;

    /**
     * @return null if any label is oversized so much that the encoder cannot encode it without integer overflow. This
     * will not return null for labels that fit within the DNS size limits.
     */
    public static @Nullable String encode(final @NonNull String string) {
        var pos = 0;
        final var limit = string.length();
        final var result = Buffer.create();

        while (pos < limit) {
            var dot = string.indexOf('.', pos);
            if (dot == -1) {
                dot = limit;
            }

            if (!encodeLabel(string, pos, dot, result)) {
                // If we couldn't encode the label, give up.
                return null;
            }

            if (dot < limit) {
                result.writeByte((byte) ((int) '.'));
                pos = dot + 1;
            } else {
                break;
            }
        }

        return result.readUtf8String();
    }

    private static boolean encodeLabel(
            final @NonNull String string,
            final @NonNegative int pos,
            final @NonNegative int limit,
            final @NonNull Buffer result
    ) {
        if (!requiresEncode(string, pos, limit)) {
            result.writeUtf8(string, pos, limit);
            return true;
        }

        result.write(PREFIX);

        final var input = codePoints(string, pos, limit);

        // Copy all the basic code points to the output.
        var b = 0;
        for (final var codePoint : input) {
            if (codePoint < INITIAL_N) {
                result.writeByte(codePoint.byteValue());
                b++;
            }
        }

        // Copy a delimiter if any basic code points were emitted.
        if (b > 0) {
            result.writeByte((byte) ((int) '-'));
        }

        var n = INITIAL_N;
        var delta = 0;
        var bias = INITIAL_BIAS;
        var h = b;
        while (h < input.size()) {
            final var currentN = n;
            final var m = input.stream()
                    .map(value -> (value >= currentN) ? value : Integer.MAX_VALUE)
                    .mapToInt(v -> v)
                    .min()
                    .getAsInt();

            final var increment = (m - n) * (h + 1);
            if (delta > Integer.MAX_VALUE - increment) {
                return false; // Prevent overflow.
            }
            delta += increment;

            n = m;

            for (final var c : input) {
                if (c < n) {
                    if (delta == Integer.MAX_VALUE) {
                        return false; // Prevent overflow.
                    }
                    delta++;
                } else if (c == n) {
                    var q = delta;

                    for (var k = BASE; true; k += BASE) {
                        final int t;
                        if (k <= bias) {
                            t = TMIN;
                        } else if (k >= bias + TMAX) {
                            t = TMAX;
                        } else {
                            t = k - bias;
                        }
                        if (q < t) {
                            break;
                        }
                        result.writeByte((byte) punycodeDigit(t + ((q - t) % (BASE - t))));
                        q = (q - t) / (BASE - t);
                    }

                    result.writeByte((byte) punycodeDigit(q));
                    bias = adapt(delta, h + 1, h == b);
                    delta = 0;
                    h++;
                }
            }
            delta++;
            n++;
        }

        return true;
    }

    /**
     * Converts a punycode-encoded domain name with `.`-separated labels into a human-readable
     * Internationalized Domain Name.
     */
    public static @Nullable String decode(final @NonNull String string) {
        var pos = 0;
        final var limit = string.length();
        final var result = Buffer.create();

        while (pos < limit) {
            var dot = string.indexOf('.', pos);
            if (dot == -1) {
                dot = limit;
            }

            if (!decodeLabel(string, pos, dot, result)) {
                return null;
            }

            if (dot < limit) {
                result.writeByte((byte) ((int) '.'));
                pos = dot + 1;
            } else {
                break;
            }
        }

        return result.readUtf8String();
    }

    /**
     * Converts a single label from Punycode to Unicode.
     *
     * @return true if the range of [string] from [pos] to [limit] was valid and decoded successfully.
     * Otherwise, the decode failed.
     */
    private static boolean decodeLabel(
            final @NonNull String string,
            final @NonNegative int pos,
            final @NonNegative int limit,
            final @NonNull Buffer result
    ) {
        if (!string.regionMatches(true, pos, PREFIX_STRING, 0, 4)) {
            result.writeUtf8(string, pos, limit);
            return true;
        }

        var pos_ = pos + 4; // 'xn--'.size.

        // We'd prefer to operate directly on `result` but it doesn't offer insertCodePoint(), only
        // appendCodePoint(). The Punycode algorithm processes code points in increasing code-point
        // order, not in increasing index order.
        final var codePoints = new ArrayList<Integer>();

        // consume all code points before the last delimiter (if there is one)
        //  and copy them to output, fail on any non-basic code point
        final var lastDelimiter = string.lastIndexOf('-', limit);
        if (lastDelimiter >= pos_) {
            while (pos_ < lastDelimiter) {
                final var codePoint = string.charAt(pos_++);
                if ((codePoint >= 'a' && codePoint <= 'z')
                        || (codePoint >= 'A' && codePoint <= 'Z')
                        || (codePoint >= '0' && codePoint <= '9')
                        || codePoint == '-') {
                    codePoints.add((int) codePoint);
                } else {
                    return false; // Malformed.
                }
            }
            pos_++; // Consume '-'.
        }

        var n = INITIAL_N;
        var i = 0;
        var bias = INITIAL_BIAS;

        while (pos_ < limit) {
            final var oldi = i;
            var w = 1;
            for (var k = BASE; true; k += BASE) {
                if (pos_ == limit) {
                    return false; // Malformed.
                }
                final var c = string.charAt(pos_++);
                final int digit;
                if (c >= 'a' && c <= 'z') {
                    digit = c - 'a';
                } else if (c >= 'A' && c <= 'Z') {
                    digit = c - 'A';
                } else if (c >= '0' && c <= '9') {
                    digit = c - '0' + 26;
                } else {
                    return false; // Malformed.
                }

                final var deltaI = digit * w;
                if (i > Integer.MAX_VALUE - deltaI) {
                    return false; // Prevent overflow.
                }
                i += deltaI;
                final int t;
                if (k <= bias) {
                    t = TMIN;
                } else if (k >= bias + TMAX) {
                    t = TMAX;
                } else {
                    t = k - bias;
                }
                if (digit < t) {
                    break;
                }
                final var scaleW = BASE - t;
                if (w > Integer.MAX_VALUE / scaleW) {
                    return false; // Prevent overflow.
                }
                w *= scaleW;
            }
            bias = adapt(i - oldi, codePoints.size() + 1, oldi == 0);
            final var deltaN = i / (codePoints.size() + 1);
            if (n > Integer.MAX_VALUE - deltaN) {
                return false; // Prevent overflow.
            }
            n += deltaN;
            i %= (codePoints.size() + 1);

            if (n > 0x10ffff) {
                return false; // Not a valid code point.
            }

            codePoints.add(i, n);

            i++;
        }

        for (final var codePoint : codePoints) {
            result.writeUtf8CodePoint(codePoint);
        }

        return true;
    }

    /**
     * @return a new bias.
     */
    private static int adapt(
            final @NonNegative int delta,
            final @NonNegative int numpoints,
            final boolean first
    ) {
        var _delta = first ? delta / DAMP : delta / 2;
        _delta += (_delta / numpoints);
        var k = 0;
        while (_delta > ((BASE - TMIN) * TMAX) / 2) {
            _delta /= (BASE - TMIN);
            k += BASE;
        }
        return k + (((BASE - TMIN + 1) * _delta) / (_delta + SKEW));
    }

    private static boolean requiresEncode(
            final @NonNull String string,
            final @NonNegative int pos,
            final @NonNegative int limit
    ) {
        for (var i = pos; i < limit; i++) {
            if (((int) string.charAt(i)) >= INITIAL_N) {
                return true;
            }
        }
        return false;
    }

    private static @NonNull List<Integer> codePoints(
            final @NonNull String string,
            final @NonNegative int pos,
            final @NonNegative int limit
    ) {
        final var result = new ArrayList<Integer>();
        var i = pos;
        while (i < limit) {
            final var c = string.charAt(i);
            if (Character.isSurrogate(c)) {
                final var low = ((i + 1) < limit) ? string.charAt(i + 1) : '\u0000';
                if (Character.isLowSurrogate(c) || !Character.isLowSurrogate(low)) {
                    result.add((int) '?');
                } else {
                    i++;
                    result.add(0x010000 + ((((int) c) & 0x03ff) << 10 | (((int) low) & 0x03ff)));
                }
            } else {
                result.add((int) c);
            }
            i++;
        }
        return result;
    }

    private static int punycodeDigit(final int value) {
        if (value < 26) {
            return value + ((int) 'a');
        }
        if (value < 36) {
            return (value - 26) + ((int) '0');
        }
        throw new IllegalStateException("unexpected digit: " + value);
    }
}
