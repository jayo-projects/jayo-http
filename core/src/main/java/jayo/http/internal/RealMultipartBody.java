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

import jayo.Buffer;
import jayo.Writer;
import jayo.bytestring.ByteString;
import jayo.http.ClientRequestBody;
import jayo.http.Headers;
import jayo.http.MediaType;
import jayo.http.MultipartBody;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.*;

import static jayo.http.MultipartBody.Part;

public final class RealMultipartBody extends AbstractList<Part> implements MultipartBody {
    private final @NonNull ByteString boundaryByteString;
    private final @NonNull MediaType type;
    private final @NonNull List<@NonNull Part> parts;

    private final @NonNull MediaType contentType;
    private long contentByteSize = -1L;

    private RealMultipartBody(final @NonNull String boundary,
                              final @NonNull MediaType type,
                              final @NonNull List<@NonNull Part> parts) {
        assert boundary != null;
        assert type != null;
        assert parts != null;

        this.boundaryByteString = ByteString.encode(boundary);
        this.type = type;
        this.parts = parts;

        this.contentType = MediaType.get(type + "; boundary=" + boundary);
    }

    @Override
    public @NonNull MediaType getType() {
        return type;
    }

    @Override
    public @NonNull String getBoundary() {
        return boundaryByteString.decodeToString();
    }

    @Override
    public int size() {
        return parts.size();
    }

    @Override
    public @NonNull Part get(final int index) {
        return parts.get(index);
    }

    @Override
    public @NonNull MediaType contentType() {
        return contentType;
    }

    @Override
    public long contentByteSize() {
        var result = contentByteSize;
        if (result == -1L) {
            result = writeOrCountBytes(null, true);
            contentByteSize = result;
        }
        return result;
    }

    @Override
    public void writeTo(final @NonNull Writer destination) {
        writeOrCountBytes(destination, false);
    }

    private static final byte @NonNull [] COLONSPACE = new byte[]{(byte) ':', (byte) ' '};
    private static final byte @NonNull [] CRLF = new byte[]{(byte) '\r', (byte) '\n'};
    private static final byte @NonNull [] DASHDASH = new byte[]{(byte) '-', (byte) '-'};

    /**
     * Either writes this request to {@code destination} or measures its content length. We have one method do
     * double-duty to make sure the counting and content are consistent, particularly when it comes to awkward
     * operations like measuring the encoded length of header strings, or the length-in-digits of an encoded integer.
     */
    private long writeOrCountBytes(final @Nullable Writer destination, boolean countBytes) {
        var byteCount = 0L;
        var dst = destination;

        Buffer byteCountBuffer = null;
        if (countBytes) {
            byteCountBuffer = Buffer.create();
            dst = byteCountBuffer;
        }

        assert dst != null;
        for (final Part part : parts) {
            final var headers = part.getHeaders();
            final var body = part.getBody();

            dst.write(DASHDASH)
                    .write(boundaryByteString)
                    .write(CRLF);

            if (headers != null) {
                for (var h = 0; h < headers.size(); h++) {
                    dst.write(headers.name(h))
                            .write(COLONSPACE)
                            .write(headers.value(h))
                            .write(CRLF);
                }
            }

            final var contentType = body.contentType();
            if (contentType != null) {
                dst.write("Content-Type: ")
                        .write(contentType.toString())
                        .write(CRLF);
            }

            // We can't measure the body's size without the sizes of its components.
            final var contentByteSize = body.contentByteSize();
            if (contentByteSize == -1L && countBytes) {
                byteCountBuffer.clear();
                return -1L;
            }

            dst.write(CRLF);

            if (countBytes) {
                byteCount += contentByteSize;
            } else {
                body.writeTo(dst);
            }

            dst.write(CRLF);
        }

        dst.write(DASHDASH);
        dst.write(boundaryByteString);
        dst.write(DASHDASH);
        dst.write(CRLF);

        if (countBytes) {
            byteCount += byteCountBuffer.bytesAvailable();
            byteCountBuffer.clear();
        }

        return byteCount;
    }

    @Override
    public boolean isOneShot() {
        for (final var part : parts) {
            if (part.getBody().isOneShot()) {
                return true;
            }
        }
        return false;
    }

    public final static class RealPart implements MultipartBody.Part {
        public static Part create(final @Nullable Headers headers, final @NonNull ClientRequestBody body) {
            if (headers != null && headers.get("Content-Type") != null) {
                throw new IllegalArgumentException("Unexpected header: Content-Type");
            }
            if (headers != null && headers.get("Content-Length") != null) {
                throw new IllegalArgumentException("Unexpected header: Content-Length");
            }
            return new RealPart(headers, body);
        }

        public static Part createFormData(final @NonNull String name,
                                          final @Nullable String filename,
                                          final @NonNull ClientRequestBody body) {
            final var dispositionSb = new StringBuilder("form-data; name=");
            appendQuotedString(dispositionSb, name);

            if (filename != null) {
                dispositionSb.append("; filename=");
                appendQuotedString(dispositionSb, filename);
            }
            final var disposition = dispositionSb.toString();

            final var headers = Headers.builder()
                    .addUnsafeNonAscii("Content-Disposition", disposition)
                    .build();

            return new RealPart(headers, body);
        }

        /**
         * Appends a quoted-string to a StringBuilder.
         * <p>
         * The RFC 2388 is rather vague about how one should escape special characters in form-data parameters, and as
         * it turns out, Firefox and Chrome actually do rather different things. Both say in their comments that they're
         * not really sure what the right approach is. We go with Chrome's behavior (which also experimentally seems to
         * match what IE does), but if you actually want to have a good chance of things working, please avoid
         * double-quotes, newlines, percent signs, and the like in your field names.
         */
        private static void appendQuotedString(final @NonNull StringBuilder builder, final @NonNull String key) {
            builder.append('"');
            for (var i = 0; i < key.length(); i++) {
                char ch = key.charAt(i);
                switch (ch) {
                    case '\n':
                        builder.append("%0A");
                        break;
                    case '\r':
                        builder.append("%0D");
                        break;
                    case '\"':
                        builder.append("%22");
                        break;
                    default:
                        builder.append(ch);
                        break;
                }
            }
            builder.append('"');
        }

        private final @Nullable Headers headers;
        private final @NonNull ClientRequestBody body;

        private RealPart(final @Nullable Headers headers, final @NonNull ClientRequestBody body) {
            assert body != null;

            this.headers = headers;
            this.body = body;
        }

        @Override
        public @Nullable Headers getHeaders() {
            return headers;
        }

        @Override
        public @NonNull ClientRequestBody getBody() {
            return body;
        }
    }

    public final static class Builder implements MultipartBody.Builder {
        private @Nullable String boundary;
        private @NonNull MediaType type = MIXED;
        private final @NonNull List<@NonNull Part> parts = new ArrayList<>();

        @Override
        public @NonNull Builder boundary(final @NonNull String boundary) {
            this.boundary = Objects.requireNonNull(boundary);
            return this;
        }

        @Override
        public @NonNull Builder type(final @NonNull MediaType type) {
            Objects.requireNonNull(type);
            if (!type.getType().equals("multipart")) {
                throw new IllegalArgumentException("multipart != " + type);
            }
            this.type = type;
            return this;
        }

        @Override
        public @NonNull Builder addPart(final @NonNull ClientRequestBody body) {
            addPart(Part.create(body));
            return this;
        }

        @Override
        public @NonNull Builder addPart(final @NonNull Headers headers, final @NonNull ClientRequestBody body) {
            addPart(Part.create(headers, body));
            return this;
        }

        @Override
        public @NonNull Builder addFormDataPart(final @NonNull String name, final @NonNull String value) {
            addPart(Part.createFormData(name, value));
            return this;
        }

        @Override
        public @NonNull Builder addFormDataPart(final @NonNull String name,
                                                final @Nullable String filename,
                                                final @NonNull ClientRequestBody body) {
            addPart(Part.createFormData(name, filename, body));
            return this;
        }

        @Override
        public @NonNull Builder addPart(final @NonNull Part part) {
            Objects.requireNonNull(part);
            parts.add(part);
            return this;
        }

        @Override
        public @NonNull MultipartBody build() {
            if (parts.isEmpty()) {
                throw new IllegalStateException("Multipart body must have at least one part.");
            }
            final var boundary = (this.boundary != null) ? this.boundary : UUID.randomUUID().toString();
            return new RealMultipartBody(boundary, type, List.copyOf(parts));
        }
    }
}
