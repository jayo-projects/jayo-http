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

import jayo.ByteString;
import jayo.Writer;
import jayo.external.NonNegative;
import jayo.http.internal.StandardClientRequestBodies;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.nio.file.Path;
import java.util.Objects;

public abstract class ClientRequestBody {
    /**
     * @return a new request body that transmits {@code content} using UTF-8 charset.
     */
    public static @NonNull ClientRequestBody create(final @NonNull String content) {
        return StandardClientRequestBodies.create(content, null);
    }

    /**
     * @return a new request body that transmits {@code content}. If {@code contentType} lacks a charset, it will use
     * UTF-8.
     */
    public static @NonNull ClientRequestBody create(final @NonNull String content,
                                                    final @NonNull MediaType contentType) {
        Objects.requireNonNull(contentType);
        return StandardClientRequestBodies.create(content, contentType);
    }

    /**
     * @return a new request body that transmits {@code content} using UTF-8 charset.
     */
    public static @NonNull ClientRequestBody create(final @NonNull ByteString content) {
        return StandardClientRequestBodies.create(content, null);
    }

    /**
     * @return a new request body that transmits {@code content}. If {@code contentType} lacks a charset, it will use
     * UTF-8.
     */
    public static @NonNull ClientRequestBody create(final @NonNull ByteString content,
                                                    final @NonNull MediaType contentType) {
        Objects.requireNonNull(contentType);
        return StandardClientRequestBodies.create(content, contentType);
    }

    /**
     * @return a new request body that transmits all bytes from {@code content} using UTF-8 charset.
     */
    public static @NonNull ClientRequestBody create(final byte @NonNull [] content) {
        Objects.requireNonNull(content);
        return create(content, 0, content.length);
    }

    /**
     * @return a new request body that transmits all bytes from {@code content}. If {@code contentType} lacks a charset,
     * it will use UTF-8.
     */
    public static @NonNull ClientRequestBody create(final byte @NonNull [] content,
                                                    final @NonNull MediaType contentType) {
        Objects.requireNonNull(content);
        Objects.requireNonNull(contentType);
        return create(content, contentType, 0, content.length);
    }

    /**
     * @return a new request body that transmits {@code byteCount} bytes from {@code content}, starting at
     * {@code offset}, using UTF-8 charset.
     * @throws IndexOutOfBoundsException if {@code offset} or {@code byteCount} is out of range of {@code content}
     *                                   indices.
     */
    public static @NonNull ClientRequestBody create(final byte @NonNull [] content,
                                                    final @NonNegative int offset,
                                                    final @NonNegative int byteCount) {
        return StandardClientRequestBodies.create(content, null, offset, byteCount);
    }

    /**
     * @return a new request body that transmits {@code byteCount} bytes from {@code content}, starting at
     * {@code offset}. If {@code contentType} lacks a charset, it will use UTF-8.
     * @throws IndexOutOfBoundsException if {@code offset} or {@code byteCount} is out of range of {@code content}
     *                                   indices.
     */
    public static @NonNull ClientRequestBody create(final byte @NonNull [] content,
                                                    final @NonNull MediaType contentType,
                                                    final @NonNegative int offset,
                                                    final @NonNegative int byteCount) {
        Objects.requireNonNull(contentType);
        return StandardClientRequestBodies.create(content, contentType, offset, byteCount);
    }

    /**
     * @return a new request body that transmits {@code content} using UTF-8 charset.
     */
    public static @NonNull ClientRequestBody create(final @NonNull Path content) {
        return StandardClientRequestBodies.create(content, null);
    }

    /**
     * @return a new request body that transmits {@code content}. If {@code contentType} lacks a charset, it will use
     * UTF-8.
     */
    public static @NonNull ClientRequestBody create(final @NonNull Path content, final @NonNull MediaType contentType) {
        Objects.requireNonNull(contentType);
        return StandardClientRequestBodies.create(content, contentType);
    }

    /**
     * @return a new request body that transmits {@code content} using UTF-8 charset.
     */
    public static @NonNull ClientRequestBody create(final @NonNull File content) {
        return StandardClientRequestBodies.create(content, null);
    }

    /**
     * @return a new request body that transmits {@code content}. If {@code contentType} lacks a charset, it will use
     * UTF-8.
     */
    public static @NonNull ClientRequestBody create(final @NonNull File content, final @NonNull MediaType contentType) {
        Objects.requireNonNull(contentType);
        return StandardClientRequestBodies.create(content, contentType);
    }

    /**
     * @return the Content-Type header for this body.
     */
    public abstract @Nullable MediaType contentType();

    /**
     * @return the number of bytes that will be written to writer in a call to {@link #writeTo(Writer)}, or -1 if that
     * count is unknown.
     */
    public long contentByteSize() {
        return -1L;
    }

    /**
     * Writes the content of this request to {@code writer}.
     */
    public abstract void writeTo(final @NonNull Writer writer);

    /**
     * A duplex request body is special in how it is <b>transmitted</b> on the network and in the <b>API contract</b>
     * between Jayo HTTP and the application.
     *
     * @return false unless it is overridden by a subclass.
     * <h3>Duplex Transmission</h3>
     * With regular HTTP calls the request always completes sending before the response may begin receiving. With duplex
     * the request and response may be interleaved! That is, request body bytes may be sent after response headers or
     * body bytes have been received.
     * <p>
     * Though any call may be initiated as a duplex call, only web servers that are specially designed for this
     * nonstandard interaction will use it. As of 2019-01, the only widely-used implementation of this pattern is
     * <a href="https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md">gRPC</a>.
     * <p>
     * Because the encoding of interleaved data is not well-defined for HTTP/1, duplex request bodies may only be used
     * with HTTP/2. Calls to HTTP/1 servers will fail before the HTTP request is transmitted. If you cannot ensure that
     * your client and server both support HTTP/2, do not use this feature.
     * <h3>Duplex APIs</h3>
     * With regular request bodies it is not legal to write bytes to the writer passed to {@link #writeTo(Writer)} after
     * that method returns. For duplex requests bodies that condition is lifted. Such writes occur on an
     * application-provided thread and may occur concurrently with reads of the {@link ClientResponseBody}. For duplex
     * request bodies, {@link #writeTo(Writer)} should return quickly, possibly by handing off the provided request body
     * to another thread to perform writing.
     */
    public boolean isDuplex() {
        return false;
    }
}
