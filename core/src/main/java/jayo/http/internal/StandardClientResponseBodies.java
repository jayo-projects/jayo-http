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

import jayo.Buffer;
import jayo.ByteString;
import jayo.Reader;
import jayo.Utf8;
import jayo.http.ClientResponseBody;
import jayo.http.MediaType;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

import static java.nio.charset.StandardCharsets.*;
import static jayo.http.internal.Utils.chooseCharset;

public final class StandardClientResponseBodies {
    // un-instantiable
    private StandardClientResponseBodies() {
    }

    public static @NonNull ClientResponseBody create(final @NonNull String string,
                                                     final @Nullable MediaType contentType) {
        Objects.requireNonNull(string);

        final var charsetMediaType = chooseCharset(contentType);
        final var buffer = Buffer.create().write(string, charsetMediaType.charset());
        return create(buffer, charsetMediaType.contentType(), buffer.bytesAvailable());
    }

    public static @NonNull ClientResponseBody create(final byte @NonNull [] bytes,
                                                     final @Nullable MediaType contentType) {
        Objects.requireNonNull(bytes);

        final var buffer = Buffer.create().write(bytes);
        return create(buffer, contentType, bytes.length);
    }

    public static @NonNull ClientResponseBody create(final @NonNull ByteString byteString,
                                                     final @Nullable MediaType contentType) {
        Objects.requireNonNull(byteString);

        if (byteString instanceof Utf8 && contentType != null && contentType.charset() != null
                && !UTF_8.equals(contentType.charset()) && !US_ASCII.equals(contentType.charset())
                && !ISO_8859_1.equals(contentType.charset())) { // latin1 is compatible with ASCII, considered fine !
            throw new IllegalArgumentException("Invalid charset for Utf8 byte string: " + contentType.charset());
        }

        final var buffer = Buffer.create().write(byteString);
        return create(buffer, contentType, byteString.byteSize());
    }

    public static @NonNull ClientResponseBody create(final @NonNull Reader reader,
                                                     final @Nullable MediaType contentType,
                                                     final long contentByteSize) {
        Objects.requireNonNull(reader);
        return new ClientResponseBody() {
            @Override
            public @Nullable MediaType contentType() {
                return contentType;
            }

            @Override
            public long contentByteSize() {
                return contentByteSize;
            }

            @Override
            public @NonNull Reader reader() {
                return reader;
            }
        };
    }
}
