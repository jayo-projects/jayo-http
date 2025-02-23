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

package jayo.http;

import jayo.http.internal.RealHeaders;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The header fields of a single HTTP message. Values are uninterpreted strings; use {@code Request} and
 * {@code Response} for interpreted headers. This class maintains the order of the header fields within the HTTP
 * message.
 * <p>
 * This class tracks header values line-by-line. This class will treat a field with multiple comma-separated values on
 * the same line as a field with a single value. It is the caller's responsibility to detect and split on commas if
 * their field permits multiple values. This simplifies the use of single-valued fields whose values routinely contain
 * commas, such as cookies or dates.
 * <p>
 * This class trims whitespace from values. It never returns values with leading or trailing whitespace.
 * <p>
 * Instances of this class are immutable. Use {@link #builder()} to create instances.
 */
public sealed interface Headers extends Collection<Headers.@NonNull Header> permits RealHeaders {
    static @NonNull Builder builder() {
        return new RealHeaders.Builder();
    }

    /**
     * Empty headers.
     */
    @NonNull
    Headers EMPTY = RealHeaders.of();

    /**
     * @return headers for the alternating header names and values. There must be an even number of arguments, and they
     * must alternate between header names and values.
     */
    static @NonNull Headers of(final @NonNull String @NonNull ... namesAndValues) {
        return RealHeaders.of(namesAndValues);
    }

    /**
     * @return headers for the header names and values in the {@code namesAndValues} map.
     */
    static @NonNull Headers of(final @NonNull Map<@NonNull String, @NonNull String> namesAndValues) {
        return RealHeaders.of(namesAndValues);
    }

    /**
     * @return the last value corresponding to the specified field, or null.
     */
    @Nullable
    String get(final @NonNull String name);

    /**
     * @return the last value corresponding to the specified field parsed as an HTTP date, or null if either the field
     * is absent or cannot be parsed as a date.
     */
    @Nullable
    Instant getInstant(final @NonNull String name);

    /**
     * @return the number of field values.
     */
    @Override
    int size();

    /**
     * @return the field at {@code index}.
     */
    @NonNull
    String name(final int index);

    /**
     * @return the value at {@code index}.
     */
    @NonNull
    String value(final int index);

    /**
     * @return an immutable case-insensitive set of all header names.
     */
    @NonNull
    Set<@NonNull String> names();

    /**
     * @return an immutable list of the header values for {@code name}.
     */
    @NonNull
    List<@NonNull String> values(final @NonNull String name);

    /**
     * @return the number of bytes required to encode these headers using HTTP/1.1. This is also the approximate size of
     * HTTP/2 headers before they are compressed with HPACK. This value is intended to be used as a metric: smaller
     * headers are more efficient to encode and transmit.
     */
    long byteCount();

    /**
     * @return a builder based on this Headers.
     */
    @NonNull
    Builder newBuilder();

    /**
     * @return true if {@code other} is a {@link Headers} object with the same headers, with the same casing, in the
     * same order. Note that two headers instances may be *semantically* equal but not equal according to this method.
     * In particular, none of the following sets of headers are equal according to this method:
     * <p>
     * 1. Original
     * <pre>
     * {@code
     * Content-Type: text/html
     * Content-Length: 50
     * }
     * </pre>
     * <p>
     * 2. Different order
     * <pre>
     * {@code
     * Content-Length: 50
     * Content-Type: text/html
     * }
     * </pre>
     * <p>
     * 3. Different case
     * <pre>
     * {@code
     * content-type: text/html
     * content-length: 50
     * }
     * </pre>
     * <p>
     * 4. Different values
     * <pre>
     * {@code
     * Content-Type: text/html
     * Content-Length: 050
     * }
     * </pre>
     * <p>
     * Applications that require semantically equal headers should convert them into a canonical form before comparing
     * them for equality.
     */
    @Override
    boolean equals(final @Nullable Object other);

    /**
     * @return header names and values. The names and values are separated by `: ` and each pair is followed by a
     * newline character `\n`.
     * <p>
     * This redacts these sensitive headers:
     * <ul>
     * <li>{@code Authorization}
     * <li>{@code Cookie}
     * <li>{@code Proxy-Authorization}
     * <li>{@code Set-Cookie}
     * </ul>
     */
    @Override
    @NonNull
    String toString();

    @NonNull
    Map<@NonNull String, @NonNull List<@NonNull String>> toMultimap();

    /**
     * The builder used to create a {@link Headers} instance.
     */
    sealed interface Builder permits RealHeaders.Builder {
        /**
         * Add a header line containing a field name, a literal colon, and a value.
         */
        @NonNull
        Builder add(final @NonNull String line);

        /**
         * Add a header with the specified name and value. Does validation of header names and values.
         */
        @NonNull
        Builder add(final @NonNull String name, final @NonNull String value);

        /**
         * Add a header with the specified name and formatted instant. Does validation of header names and value.
         */
        @NonNull
        Builder add(final @NonNull String name, final @NonNull Instant value);

        /**
         * Add a header with the specified name and value. Does validation of header names, allowing non-ASCII values.
         */
        @NonNull
        Builder addUnsafeNonAscii(final @NonNull String name, final @NonNull String value);

        /**
         * Add all headers from an existing collection.
         */
        @NonNull
        Builder addAll(final @NonNull Headers headers);

        /**
         * Set a field with the specified value. If the field is not found, it is added. If the field is found, the
         * existing values are replaced.
         */
        @NonNull
        Builder set(final @NonNull String name, final @NonNull String value);

        /**
         * Set a field with the specified instant. If the field is not found, it is added. If the field is found, the
         * existing values are replaced.
         */
        @NonNull
        Builder set(final @NonNull String name, final @NonNull Instant value);

        /**
         * Remove all values of a given header name.
         */
        @NonNull
        Builder removeAll(final @NonNull String name);

        /**
         * Equivalent to {@code build().get(name)}, but potentially faster.
         */
        @Nullable
        String get(final @NonNull String name);

        @NonNull
        Headers build();
    }

    record Header(@NonNull String name, @NonNull String value) {
    }
}
