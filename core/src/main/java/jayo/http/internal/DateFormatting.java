/*
 * Copyright (c) 2024-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 *
 * Forked from OkHttp (https://github.com/square/okhttp), original copyright is below
 *
 * Copyright (C) 2011 The Android Open Source Project
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

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.text.ParsePosition;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Locale;

import static java.time.ZoneOffset.UTC;

public final class DateFormatting {
    // un-instantiable
    private DateFormatting() {
    }

    /**
     * GMT and UTC are equivalent for our purposes.
     */
    private static final @NonNull DateTimeFormatter STANDARD_DATE_FORMAT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                    .withResolverStyle(ResolverStyle.LENIENT)
                    .withZone(UTC);

    /**
     * If we fail to parse a date in a non-standard format, try each of these formats in sequence.
     */
    private static final @NonNull String @NonNull [] BROWSER_COMPATIBLE_DATE_FORMAT_STRINGS =
            new String[]{
                    // HTTP formats required by RFC2616 but with any timezone:
                    // RFC 822, updated by RFC 1123 with any TZ.
                    "EEE, dd MMM yyyy HH:mm:ss zzz",
                    // RFC 850, obsoleted by RFC 1036 with any TZ.
                    "EEEE, dd-MMM-yy HH:mm:ss zzz",
                    // ANSI C's asctime() format
                    "EEE MMM d HH:mm:ss yyyy",
                    // Alternative formats:
                    "EEE, dd-MMM-yyyy HH:mm:ss z",
                    "EEE, dd-MMM-yyyy HH-mm-ss z",
                    "EEE, dd MMM yy HH:mm:ss z",
                    "EEE dd-MMM-yyyy HH:mm:ss z",
                    "EEE dd MMM yyyy HH:mm:ss z",
                    "EEE dd-MMM-yyyy HH-mm-ss z",
                    "EEE dd-MMM-yy HH:mm:ss z",
                    "EEE dd MMM yy HH:mm:ss z",
                    "EEE,dd-MMM-yy HH:mm:ss z",
                    "EEE,dd-MMM-yyyy HH:mm:ss z",
                    "EEE, dd-MM-yyyy HH:mm:ss z",
                    // RI bug 6641315 claims a cookie of this format was once served by www.yahoo.com:
                    "EEE MMM d yyyy HH:mm:ss z"
            };

    private static final @Nullable DateTimeFormatter @NonNull [] BROWSER_COMPATIBLE_DATE_FORMATS =
            new DateTimeFormatter[BROWSER_COMPATIBLE_DATE_FORMAT_STRINGS.length];

    public static @Nullable Instant toHttpInstantOrNull(final @NonNull String instantAsString) {
        assert instantAsString != null;

        if (instantAsString.isBlank()) {
            return null;
        }

        final var position = new ParsePosition(0);
        var result = STANDARD_DATE_FORMAT.parse(instantAsString, position);
        if (position.getIndex() == instantAsString.length()) {
            // STANDARD_DATE_FORMAT must match exactly; all text must be consumed, e.g. no ignored
            // non-standard trailing "+01:00". Those cases are covered below.
            return Instant.from(result);
        }
        for (var i = 0; i < BROWSER_COMPATIBLE_DATE_FORMAT_STRINGS.length; i++) {
            var format = BROWSER_COMPATIBLE_DATE_FORMATS[i];
            if (format == null) {
                format = DateTimeFormatter.ofPattern(BROWSER_COMPATIBLE_DATE_FORMAT_STRINGS[i], Locale.US)
                        // Set the timezone to use when interpreting formats that don't have a timezone. GMT is
                        // specified by RFC 7231.
                        .withZone(UTC);
                BROWSER_COMPATIBLE_DATE_FORMATS[i] = format;
            }
            position.setIndex(0);
            result = format.parse(instantAsString, position);
            if (position.getIndex() != 0) {
                // Something was parsed. It's possible the entire string was not consumed, but we ignore that. If
                // any of the BROWSER_COMPATIBLE_DATE_FORMAT_STRINGS ended in "'GMT'" we'd have to also check that
                // position.getIndex() == value.length() otherwise parsing might have terminated early, ignoring
                // things like "+01:00". Leaving this as != 0 means that any trailing junk is ignored.
                return Instant.from(result);
            }
        }
        return null;
    }

    /**
     * @return the string for this date.
     */
    static @NonNull String toHttpInstantString(final @NonNull Instant instant) {
        assert instant != null;
        return STANDARD_DATE_FORMAT.format(instant);
    }
}
