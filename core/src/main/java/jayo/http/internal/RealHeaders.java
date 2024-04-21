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
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.*;

import static jayo.http.internal.DateFormatting.toHttpInstantOrNull;
import static jayo.http.internal.DateFormatting.toHttpInstantString;
import static jayo.http.internal.Utils.isSensitiveHeader;

public final class RealHeaders extends AbstractCollection<Headers.@NonNull Header> implements Headers {
    private final @NonNull String @NonNull [] namesAndValues;

    private RealHeaders(final @NonNull String @NonNull [] namesAndValues) {
        this.namesAndValues = Objects.requireNonNull(namesAndValues);
    }

    @Override
    public @Nullable String get(final @NonNull String name) {
        Objects.requireNonNull(name);
        for (var i = namesAndValues.length - 2; i >= 0; i -= 2) {
            if (name.equalsIgnoreCase(namesAndValues[i])) {
                return namesAndValues[i + 1];
            }
        }
        return null;
    }

    @Override
    public @Nullable Instant getInstant(final @NonNull String name) {
        final var valueAsString = get(name);
        return (valueAsString != null) ? toHttpInstantOrNull(valueAsString) : null;
    }

    @Override
    public int size() {
        return namesAndValues.length / 2;
    }

    @Override
    public @NonNull String name(final @NonNegative int index) {
        if (index < 0 || (index * 2) >= namesAndValues.length) {
            throw new IndexOutOfBoundsException("name[ " + index + "]");
        }
        return namesAndValues[index * 2];
    }

    @Override
    public @NonNull String value(final @NonNegative int index) {
        if (index < 0 || (index * 2 + 1) >= namesAndValues.length) {
            throw new IndexOutOfBoundsException("value[ " + index + "]");
        }
        return namesAndValues[index * 2 + 1];
    }

    @Override
    public @NonNull Set<@NonNull String> names() {
        final var result = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (var i = 0; i < size(); i++) {
            result.add(name(i));
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public @NonNull List<@NonNull String> values(final @NonNull String name) {
        List<String> result = null;
        for (var i = 0; i < size(); i++) {
            if (name.equalsIgnoreCase(name(i))) {
                if (result == null) {
                    result = new ArrayList<>(2);
                }
                result.add(value(i));
            }
        }
        return (result != null) ? Collections.unmodifiableList(result) : List.of();
    }

    @Override
    public long byteCount() {
        // Each header name has 2 bytes of overhead for ': ' and every header value has 2 bytes of
        // overhead for '\r\n'.
        var result = namesAndValues.length * 2L;

        for (String namesAndValue : namesAndValues) {
            result += namesAndValue.length();
        }

        return result;
    }

    @Override
    public @NonNull Iterator<Headers.@NonNull Header> iterator() {
        final var array = new Headers.Header[size()];
        Arrays.setAll(array, i -> new Header(name(i), value(i)));
        return Arrays.stream(array).iterator();
    }

    @Override
    public Headers.@NonNull Builder newBuilder() {
        final var result = new Builder();
        result.namesAndValues.addAll(List.of(namesAndValues));
        return result;
    }

    @Override
    public boolean equals(final @Nullable Object other) {
        return (other instanceof RealHeaders otherAsHeaders)
                && Arrays.equals(namesAndValues, otherAsHeaders.namesAndValues);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(namesAndValues);
    }

    @Override
    public @NonNull String toString() {
        final var sb = new StringBuilder();
        for (var i = 0; i < size(); i++) {
            final var name = name(i);
            final var value = value(i);
            sb.append(name);
            sb.append(": ");
            sb.append((isSensitiveHeader(name)) ? "██" : value);
            sb.append("\n");
        }
        return sb.toString();
    }

    @Override
    public @NonNull Map<@NonNull String, @NonNull List<@NonNull String>> toMultimap() {
        final var result = new TreeMap<String, List<String>>(String.CASE_INSENSITIVE_ORDER);
        for (var i = 0; i < size(); i++) {
            final var name = name(i).toLowerCase(Locale.US);
            var values = result.computeIfAbsent(name, k -> new ArrayList<>(2));
            values.add(value(i));
        }
        return result;
    }

    public static @NonNull Headers of(final @NonNull String @NonNull ... inputNamesAndValues) {
        Objects.requireNonNull(inputNamesAndValues);
        if (inputNamesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("Expected alternating header names and values");
        }

        // Make a defensive copy and clean it up.
        final var namesAndValues = new String[inputNamesAndValues.length];
        var i = 0;
        for (final var nameAndValue : inputNamesAndValues) {
            if (nameAndValue == null) {
                throw new IllegalArgumentException("Headers cannot be null");
            }
            namesAndValues[i++] = nameAndValue.trim();
        }

        // Check for malformed headers.
        for (i = 0; i < namesAndValues.length; i += 2) {
            final var name = namesAndValues[i];
            final var value = namesAndValues[i + 1];
            headersCheckName(name);
            headersCheckValue(value, name);
        }

        return new RealHeaders(namesAndValues);
    }

    public static @NonNull Headers of(@NonNull Map<@NonNull String, @NonNull String> inputNamesAndValues) {
        Objects.requireNonNull(inputNamesAndValues);
        // Make a defensive copy and clean it up.
        final var namesAndValues = new String[inputNamesAndValues.size() * 2];
        var i = 0;
        for (final var nameAndValueEntry : inputNamesAndValues.entrySet()) {
            final var name = nameAndValueEntry.getKey().trim();
            final var value = nameAndValueEntry.getValue().trim();
            headersCheckName(name);
            headersCheckValue(value, name);
            namesAndValues[i++] = name;
            namesAndValues[i++] = value;
        }

        return new RealHeaders(namesAndValues);
    }

    private static void headersCheckName(final @NonNull String name) {
        if (name.isEmpty()) {
            throw new IllegalArgumentException("name is empty");
        }
        for (var i = 0; i < name.length(); i++) {
            final var c = name.charAt(i);
            if (c < '\u0021' || c > '\u007e') {
                throw new IllegalArgumentException(
                        "Unexpected char 0x" + charCode(c) + " at " + i + " in header name: " + name);
            }
        }
    }

    private static void headersCheckValue(final @NonNull String value, final @NonNull String name) {
        for (var i = 0; i < value.length(); i++) {
            final var c = value.charAt(i);
            if (c != '\t' && (c < '\u0020' || c > '\u007e')) {
                throw new IllegalArgumentException(
                        "Unexpected char 0x" + charCode(c) + " at " + i + " in header " + name +
                                " value" + ((isSensitiveHeader(name)) ? "" : ": " + value));
            }
        }
    }

    private static @NonNull String charCode(final char character) {
        final var hexCode = Integer.toString(character, 16);
        if (hexCode.length() < 2) {
            return "0" + hexCode;
        }
        return hexCode;
    }

    public static final class Builder implements Headers.Builder {
        private final List<String> namesAndValues = new ArrayList<>(20);

        @Override
        public Headers.@NonNull Builder add(final @NonNull String line) {
            Objects.requireNonNull(line);
            final var index = line.indexOf(':');
            if (index == -1) {
                throw new IllegalArgumentException("Unexpected header: " + line);
            }
            return add(line.substring(0, index).trim(), line.substring(index + 1));
        }

        @Override
        public Headers.@NonNull Builder add(final @NonNull String name, final @NonNull String value) {
            Objects.requireNonNull(name);
            Objects.requireNonNull(value);
            headersCheckName(name);
            headersCheckValue(value, name);
            return addLenient(name, value);
        }

        @Override
        public Headers.@NonNull Builder add(final @NonNull String name, final @NonNull Instant value) {
            Objects.requireNonNull(name);
            Objects.requireNonNull(value);
            final var stringValue = toHttpInstantString(value);
            return add(name, stringValue);
        }

        @Override
        public Headers.@NonNull Builder addUnsafeNonAscii(final @NonNull String name, final @NonNull String value) {
            Objects.requireNonNull(name);
            Objects.requireNonNull(value);
            headersCheckName(name);
            return addLenient(name, value);
        }

        @Override
        public Headers.@NonNull Builder addAll(final @NonNull Headers headers) {
            for (var i = 0; i < headers.size(); i++) {
                addLenient(headers.name(i), headers.value(i));
            }
            return this;
        }

        @Override
        public Headers.@NonNull Builder set(final @NonNull String name, final @NonNull String value) {
            headersCheckName(name);
            headersCheckValue(value, name);
            removeAll(name);
            return addLenient(name, value);
        }

        @Override
        public Headers.@NonNull Builder set(final @NonNull String name, final @NonNull Instant value) {
            Objects.requireNonNull(name);
            Objects.requireNonNull(value);
            final var stringValue = toHttpInstantString(value);
            return set(name, stringValue);
        }

        @Override
        public Headers.@NonNull Builder removeAll(@NonNull String name) {
            Objects.requireNonNull(name);
            var i = 0;
            while (i < namesAndValues.size()) {
                if (name.equalsIgnoreCase(namesAndValues.get(i))) {
                    namesAndValues.remove(i); // name
                    namesAndValues.remove(i); // value
                    i -= 2;
                }
                i += 2;
            }
            return this;
        }

        @Override
        public @Nullable String get(final @NonNull String name) {
            Objects.requireNonNull(name);
            for (var i = namesAndValues.size() - 2; i >= 0; i -= 2) {
                if (name.equalsIgnoreCase(namesAndValues.get(i))) {
                    return namesAndValues.get(i + 1);
                }
            }
            return null;
        }

        @Override
        public @NonNull Headers build() {
            return new RealHeaders(namesAndValues.toArray(String[]::new));
        }

        /**
         * Add a header line without any validation. Only appropriate for headers from the remote peer or cache.
         */
        Headers.@NonNull Builder addLenient(final @NonNull String line) {
            Objects.requireNonNull(line);
            final var index = line.indexOf(':', 1);
            if (index != -1) {
                addLenient(line.substring(0, index), line.substring(index + 1));
            /*} else if (line.charAt(0) == ':') {
                    // Work around empty header names and header names that start with a colon (created by old
                    // broken SPDY versions of the response cache).
                    addLenient("", line.substring(1)); // Empty header name.*/
            } else {
                // No header name.
                addLenient("", line);
            }
            return this;
        }

        Headers.@NonNull Builder addLenient(final @NonNull String name, final @NonNull String value) {
            Objects.requireNonNull(name);
            Objects.requireNonNull(value);
            namesAndValues.add(name);
            namesAndValues.add(value.trim());
            return this;
        }
    }
}
