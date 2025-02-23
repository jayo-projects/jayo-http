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

import jayo.JayoException;
import jayo.Reader;
import jayo.bytestring.ByteString;
import jayo.http.ClientResponseBody;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import static java.nio.charset.StandardCharsets.UTF_8;

public final class ClientResponseBodyUtils {
    // un-instantiable
    private ClientResponseBodyUtils() {
    }

    public static byte @NonNull [] consumeToBytes(final @NonNull ClientResponseBody responseBody) {
        return consumeReader(responseBody, Reader::readByteArray, bytes -> bytes.length);
    }

    public static @NonNull ByteString consumeToByteString(final @NonNull ClientResponseBody responseBody) {
        return consumeReader(responseBody, Reader::readByteString, ByteString::byteSize);
    }

    private static <T> T consumeReader(final @NonNull ClientResponseBody responseBody,
                                       final @NonNull Function<Reader, T> consumer,
                                       final @NonNull ToIntFunction<T> byteSizeMapper) {
        assert responseBody != null;
        assert consumer != null;
        assert byteSizeMapper != null;

        final var contentByteSize = responseBody.contentByteSize();
        if (contentByteSize > Integer.MAX_VALUE) {
            throw new JayoException("Cannot buffer entire body for content byte byteSize: " + contentByteSize);
        }

        final T bytes;
        try (final var reader = responseBody.reader()) {
            bytes = consumer.apply(reader);
        }
        final var byteSize = byteSizeMapper.applyAsInt(bytes);
        if (contentByteSize != -1L && contentByteSize != byteSize) {
            throw new JayoException("Content-Length (" + contentByteSize + ") and stream length ("
                    + byteSize + ") disagree");
        }
        return bytes;
    }

    public static @NonNull String consumeToString(final @NonNull ClientResponseBody responseBody) {
        final var contentByteSize = responseBody.contentByteSize();
        if (contentByteSize > Integer.MAX_VALUE) {
            throw new JayoException("Cannot buffer entire body for content byte byteSize: " + contentByteSize);
        }

        try (final var reader = responseBody.reader()) {
            final var charset = Utils.readBomAsCharset(reader, charsetOrUtf8(responseBody));
            return reader.readString(charset);
        }
    }

    public static final class BomAwareReader extends java.io.Reader {
        private final @NonNull Reader reader;
        private final @NonNull Charset charset;
        private boolean closed = false;
        private java.io.@Nullable Reader delegate = null;

        public BomAwareReader(final @NonNull ClientResponseBody responseBody) {
            this.reader = responseBody.reader();
            this.charset = charsetOrUtf8(responseBody);
        }

        @Override
        public int read(final char @NonNull [] cbuf, final int off, final int len) throws IOException {
            if (closed) {
                throw new IOException("Stream closed");
            }

            if (delegate == null) {
                delegate = new InputStreamReader(
                        reader.asInputStream(),
                        Utils.readBomAsCharset(reader, charset)
                );
            }
            return delegate.read(cbuf, off, len);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            if (delegate != null) {
                delegate.close();
            } else {
                try {
                    reader.close();
                } catch (JayoException e) {
                    throw e.getCause();
                }
            }
        }
    }

    private static @NonNull Charset charsetOrUtf8(final @NonNull ClientResponseBody responseBody) {
        assert responseBody != null;

        final var contentType = responseBody.contentType();
        if (contentType == null || contentType.charset() == null) {
            return UTF_8;
        }

        //noinspection DataFlowIssue
        return contentType.charset();
    }
}
