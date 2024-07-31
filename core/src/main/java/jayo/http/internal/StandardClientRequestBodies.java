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
import jayo.Jayo;
import jayo.Writer;
import jayo.external.NonNegative;
import jayo.http.ClientRequestBody;
import jayo.http.MediaType;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static jayo.external.JayoUtils.checkOffsetAndCount;
import static jayo.http.internal.Utils.chooseCharset;

public final class StandardClientRequestBodies {
    // un-instantiable
    private StandardClientRequestBodies() {
    }

    public static @NonNull ClientRequestBody create(final @NonNull String string,
                                                    final @Nullable MediaType contentType) {
        Objects.requireNonNull(string);
        final var charsetMediaType = chooseCharset(contentType);
        final var bytes = string.getBytes(charsetMediaType.charset());
        return create(bytes, charsetMediaType.contentType(), 0, bytes.length);
    }

    public static @NonNull ClientRequestBody create(final @NonNull ByteString byteString,
                                                    final @Nullable MediaType contentType) {
        Objects.requireNonNull(byteString);
        return new ClientRequestBody() {
            @Override
            public @Nullable MediaType contentType() {
                return contentType;
            }

            @Override
            public long contentByteSize() {
                return byteString.byteSize();
            }

            @Override
            public void writeTo(final @NonNull Writer writer) {
                writer.write(byteString);
            }
        };
    }

    public static @NonNull ClientRequestBody create(final byte @NonNull [] bytes,
                                                    final @Nullable MediaType contentType,
                                                    final @NonNegative int offset,
                                                    final @NonNegative int byteCount) {
        Objects.requireNonNull(bytes);
        checkOffsetAndCount(bytes.length, offset, byteCount);
        return new ClientRequestBody() {
            @Override
            public @Nullable MediaType contentType() {
                return contentType;
            }

            @Override
            public long contentByteSize() {
                return byteCount;
            }

            @Override
            public void writeTo(final @NonNull Writer writer) {
                writer.write(bytes, offset, byteCount);
            }
        };
    }

    public static @NonNull ClientRequestBody create(final @NonNull Path path,
                                                    final @Nullable MediaType contentType) {
        Objects.requireNonNull(path);
        return new ClientRequestBody() {
            @Override
            public @Nullable MediaType contentType() {
                return contentType;
            }

            @Override
            public long contentByteSize() {
                try {
                    return (Files.isRegularFile(path)) ? Files.size(path) : -1L;
                } catch (IOException _unused) {
                    return -1L;
                }
            }

            @Override
            public void writeTo(final @NonNull Writer writer) {
                try (final var reader = Jayo.reader(path)) {
                    writer.transferFrom(reader);
                }
            }
        };
    }

    public static @NonNull ClientRequestBody create(final @NonNull File file,
                                                    final @Nullable MediaType contentType) {
        Objects.requireNonNull(file);
        return new ClientRequestBody() {
            @Override
            public @Nullable MediaType contentType() {
                return contentType;
            }

            @Override
            public long contentByteSize() {
                return file.length();
            }

            @Override
            public void writeTo(final @NonNull Writer writer) {
                try (final var reader = Jayo.reader(file)) {
                    writer.transferFrom(reader);
                }
            }
        };
    }
}
