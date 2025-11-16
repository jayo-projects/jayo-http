/*
 * Copyright (c) 2025-present, pull-vert and Jayo contributors.
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

import jayo.*;
import jayo.bytestring.ByteString;
import jayo.http.ClientResponseBody;
import jayo.http.Headers;
import jayo.http.MultipartReader;
import jayo.http.internal.http1.HeadersReader;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import static jayo.tools.JayoUtils.JAYO_BUFFER_SEGMENT_SIZE;

public final class RealMultipartReader implements MultipartReader {
    /**
     * These options follow the boundary.
     */
    private static final @NonNull Options AFTER_BOUNDARY_OPTIONS = Options.of(
            // 0.  "\r\n"  More parts.
            ByteString.encode("\r\n"),
            // 1.  "--"    No more parts.
            ByteString.encode("--"),
            // 2.  " "     Optional whitespace. Only used if there are more parts.
            ByteString.encode(" "),
            // 3.  "\t"    Optional whitespace. Only used if there are more parts.
            ByteString.encode("\t")
    );

    private final @NonNull Reader reader;
    private final @NonNull String boundary;

    /**
     * This delimiter typically precedes the first part.
     */
    private final @NonNull ByteString dashDashBoundary;

    /**
     * This delimiter typically precedes all subsequent parts. It may also precede the first part if the body contains a
     * preamble.
     */
    private final @NonNull ByteString crlfDashDashBoundary;

    private int partCount = 0;
    private boolean closed = false;
    private boolean noMoreParts = false;

    /**
     * This is the only part allowed to read from the underlying reader.
     */
    private @Nullable PartRawReader currentPart = null;

    public RealMultipartReader(final @NonNull ClientResponseBody responseBody) {
        assert responseBody != null;

        final var responseContentType = responseBody.contentType();
        if (responseContentType == null) {
            throw new JayoProtocolException("expected the Content-Type to have a boundary parameter");
        }
        final var boundary = responseContentType.parameter("boundary");
        if (boundary == null) {
            throw new JayoProtocolException("expected the Content-Type to have a boundary parameter");
        }

        this.reader = responseBody.reader();
        this.boundary = boundary;

        dashDashBoundary = Buffer.create()
                .write("--")
                .write(boundary)
                .readByteString();
        crlfDashDashBoundary = Buffer.create()
                .write("\r\n--")
                .write(boundary)
                .readByteString();
    }

    RealMultipartReader(final @NonNull Reader reader, final @NonNull String boundary) {
        assert reader != null;
        assert boundary != null;

        this.reader = reader;
        this.boundary = boundary;

        dashDashBoundary = Buffer.create()
                .write("--")
                .write(boundary)
                .readByteString();
        crlfDashDashBoundary = Buffer.create()
                .write("\r\n--")
                .write(boundary)
                .readByteString();
    }

    @Override
    public @NonNull String getBoundary() {
        return boundary;
    }

    @Override
    public @Nullable Part nextPart() {
        if (closed) {
            throw new JayoClosedResourceException();
        }

        if (noMoreParts) {
            return null;
        }

        // Read a boundary, skipping the remainder of the preceding part as necessary.
        if (partCount == 0 && reader.rangeEquals(0L, dashDashBoundary)) {
            // This is the first part. Consume "--" followed by the boundary.
            reader.skip(dashDashBoundary.byteSize());
        } else {
            // This is a subsequent part or a preamble. Skip until "\r\n--" followed by the boundary.
            while (true) {
                final var toSkip = currentPartBytesRemaining(JAYO_BUFFER_SEGMENT_SIZE);
                if (toSkip == 0L) {
                    break;
                }
                reader.skip(toSkip);
            }
            reader.skip(crlfDashDashBoundary.byteSize());
        }

        // Read either \r\n or --\r\n to determine if there is another part.
        var whitespace = false;
        afterBoundaryLoop:
        while (true) {
            switch (reader.select(AFTER_BOUNDARY_OPTIONS)) {
                case 0 -> {
                    // "\r\n": We've found a new part.
                    partCount++;
                    break afterBoundaryLoop;
                }
                case 1 -> {
                    // "--": No more parts.
                    if (whitespace) {
                        throw new JayoProtocolException("unexpected characters after boundary");
                    }
                    if (partCount == 0) {
                        throw new JayoProtocolException("expected at least 1 part");
                    }
                    noMoreParts = true;
                    return null;
                }
                case 2, 3 -> whitespace = true; // " " or "\t" Ignore whitespace and keep looking.
                default -> throw new JayoProtocolException("unexpected characters after boundary");
            }
        }

        // There's another part. Parse its headers and return it.
        final var headers = new HeadersReader(reader).readHeaders();
        currentPart = new PartRawReader();
        return new Part(headers, Jayo.buffer(currentPart));
    }

    /**
     * A single part in the stream. It is an error to read this after calling {@link #nextPart()}.
     */
    private class PartRawReader implements RawReader {
        @Override
        public long readAtMostTo(final @NonNull Buffer destination, final long byteCount) {
            assert destination != null;

            if (byteCount < 0L) {
                throw new IllegalArgumentException("byteCount < 0: " + byteCount);
            }
            if (currentPart != this) {
                throw new JayoClosedResourceException();
            }

            final var limit = currentPartBytesRemaining(byteCount);
            if (limit == 0L) {
                return -1L; // No more bytes in this part.
            }
            return reader.readAtMostTo(destination, limit);
        }

        @Override
        public void close() {
            if (currentPart == this) {
                currentPart = null;
            }
        }
    }

    /**
     * Returns a value in {@code 0..maxByteCount} with the number of bytes that can be read from {@link #reader} in the
     * current part. If this returns 0, the current part is exhausted; otherwise it has at least one byte left to read.
     */
    private long currentPartBytesRemaining(final long maxByteCount) {
        long toIndex = Math.min(reader.bytesAvailable(), maxByteCount) + 1L;
        long boundaryIndex =
                reader.indexOf(
                        /*byteString*/ crlfDashDashBoundary,
                        /*startIndex*/ 0L,
                        /* endIndex*/ toIndex);
        if (boundaryIndex != -1L) {
            return boundaryIndex; // We found the boundary.
        } else if (reader.bytesAvailable() >= toIndex) {
            return Math.min(toIndex, maxByteCount); // No boundary before toIndex.
        }
        throw new JayoEOFException(); // We ran out of data before we found the required boundary.
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        currentPart = null;
        reader.close();
    }

    public static final class Part implements MultipartReader.Part {
        private final @NonNull Headers headers;
        private final @NonNull Reader body;

        private Part(final @NonNull Headers headers, final @NonNull Reader body) {
            assert headers != null;
            assert body != null;

            this.headers = headers;
            this.body = body;
        }

        @Override
        public @NonNull Headers getHeaders() {
            return headers;
        }

        @Override
        public @NonNull Reader getBody() {
            return body;
        }

        @Override
        public void close() {
            body.close();
        }
    }
}
