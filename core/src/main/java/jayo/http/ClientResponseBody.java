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

import jayo.Buffer;
import jayo.Reader;
import jayo.bytestring.ByteString;
import jayo.http.internal.StandardClientResponseBodies;
import jayo.http.tools.JayoHttpUtils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.InputStream;
import java.util.Objects;

import static jayo.http.internal.ClientResponseBodyUtils.*;

public abstract class ClientResponseBody implements AutoCloseable {
    /**
     * The empty response body.
     */
    public static final @NonNull ClientResponseBody EMPTY = new EmptyClientResponseBody();

    /**
     * @return a new response body that transmits {@code content} using UTF-8 charset.
     */
    public static @NonNull ClientResponseBody create(final @NonNull String content) {
        return StandardClientResponseBodies.create(content, null);
    }

    /**
     * @return a new response body that transmits {@code content}. If {@code contentType} lacks a charset, it will use
     * UTF-8.
     */
    public static @NonNull ClientResponseBody create(final @NonNull String content,
                                                     final @NonNull MediaType contentType) {
        Objects.requireNonNull(contentType);
        return StandardClientResponseBodies.create(content, contentType);
    }

    /**
     * @return a new response body that transmits {@code content} using UTF-8 charset.
     */
    public static @NonNull ClientResponseBody create(final @NonNull ByteString content) {
        return StandardClientResponseBodies.create(content, null);
    }

    /**
     * @return a new response body that transmits {@code content}. If {@code contentType} lacks a charset, it will use
     * UTF-8.
     */
    public static @NonNull ClientResponseBody create(final @NonNull ByteString content,
                                                     final @NonNull MediaType contentType) {
        Objects.requireNonNull(contentType);
        return StandardClientResponseBodies.create(content, contentType);
    }

    /**
     * @return a new response body that transmits all bytes from {@code content} using UTF-8 charset.
     */
    public static @NonNull ClientResponseBody create(final byte @NonNull [] content) {
        return StandardClientResponseBodies.create(content, null);
    }

    /**
     * @return a new response body that transmits all bytes from {@code content}. If {@code contentType} lacks a
     * charset, it will use UTF-8.
     */
    public static @NonNull ClientResponseBody create(final byte @NonNull [] content,
                                                     final @NonNull MediaType contentType) {
        Objects.requireNonNull(contentType);
        return StandardClientResponseBodies.create(content, contentType);
    }

    /**
     * @return a new response body that transmits all bytes from {@code reader} using UTF-8 charset.
     */
    public static @NonNull ClientResponseBody create(final @NonNull Reader reader) {
        return StandardClientResponseBodies.create(reader, (MediaType) null, -1L);
    }

    /**
     * @return a new response body that transmits all bytes from {@code reader}. If {@code contentType} lacks a charset,
     * it will use UTF-8.
     */
    public static @NonNull ClientResponseBody create(final @NonNull Reader reader,
                                                     final @NonNull MediaType contentType) {
        Objects.requireNonNull(contentType);
        return StandardClientResponseBodies.create(reader, contentType, -1L);
    }

    /**
     * @return a new response body that transmits {@code contentByteSize} bytes from {@code reader} using UTF-8 charset.
     */
    public static @NonNull ClientResponseBody create(final @NonNull Reader reader, final long contentByteSize) {
        return StandardClientResponseBodies.create(reader, (MediaType) null, contentByteSize);
    }

    /**
     * @return a new response body that transmits {@code contentByteSize} bytes from {@code reader}. If
     * {@code contentType} lacks a charset, it will use UTF-8.
     */
    public static @NonNull ClientResponseBody create(final @NonNull Reader reader,
                                                     final @NonNull MediaType contentType,
                                                     final long contentByteSize) {
        Objects.requireNonNull(contentType);
        return StandardClientResponseBodies.create(reader, contentType, contentByteSize);
    }

    /**
     * Multiple calls to {@link #charStream()} must return the same instance.
     */
    private java.io.@Nullable Reader reader = null;

    /**
     * @return the Content-Type header for this body.
     */
    public abstract @Nullable MediaType contentType();

    /**
     * @return the number of bytes that will be returned by {@link #bytes()}, or {@link #byteStream()}, or -1 if that
     * count is unknown.
     */
    public abstract long contentByteSize();

    public abstract @NonNull Reader reader();

    /**
     * @return the response as an input stream.
     */
    public final @NonNull InputStream byteStream() {
        return reader().asInputStream();
    }

    /**
     * @return the response as a character stream.
     * <ul>
     * <li>If the response starts with a
     * <a href="https://en.wikipedia.org/wiki/Byte_order_mark">Byte Order Mark (BOM)</a>, it is consumed and used to
     * determine the charset of the response bytes.
     * <li>Otherwise, if the response has a {@code Content-Type} header that specifies a charset, that is used to
     * determine the charset of the response bytes.
     * <li>Otherwise, the response bytes are decoded as UTF-8.
     * </ul>
     */
    public final java.io.@NonNull Reader charStream() {
        if (reader == null) {
            reader = new BomAwareReader(this);
        }
        return reader;
    }

    /**
     * @return the response as a byte array.
     * <p>
     * Note: This method loads the entire response body into memory. If the response body is very large, this may
     * trigger an {@link OutOfMemoryError}. Prefer to stream the response body if this is a possibility for your
     * response.
     */
    public final byte @NonNull [] bytes() {
        return consumeToBytes(this);
    }

    /**
     * @return the response as a {@link ByteString}.
     * <p>
     * This method loads the entire response body into memory. If the response body is very large, this may trigger an
     * {@link OutOfMemoryError}. Prefer to stream the response body if this is a possibility for your response.
     */
    public final @NonNull ByteString byteString() {
        return consumeToByteString(this);
    }

    /**
     * @return the response as a string.
     * <ul>
     * <li>If the response starts with a
     * <a href="https://en.wikipedia.org/wiki/Byte_order_mark">Byte Order Mark (BOM)</a>, it is consumed and used to
     * determine the charset of the response bytes.
     * <li>Otherwise, if the response has a {@code Content-Type} header that specifies a charset, that is used to
     * determine the charset of the response bytes.
     * <li>Otherwise, the response bytes are decoded as UTF-8.
     * </ul>
     * This method loads the entire response body into memory. If the response body is very large, this may trigger an
     * {@link OutOfMemoryError}. Prefer to stream the response body if this is a possibility for your response.
     */
    public final @NonNull String string() {
        return consumeToString(this);
    }

    @Override
    public void close() {
        JayoHttpUtils.closeQuietly(reader());
    }

    private static final class EmptyClientResponseBody extends ClientResponseBody {
        @Override
        public @Nullable MediaType contentType() {
            return null;
        }

        @Override
        public long contentByteSize() {
            return 0L;
        }

        @Override
        public @NonNull Reader reader() {
            return Buffer.create();
        }
    }
}
