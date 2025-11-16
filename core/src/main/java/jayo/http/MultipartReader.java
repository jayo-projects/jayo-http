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

package jayo.http;

import jayo.Reader;
import jayo.http.internal.RealMultipartReader;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * Reads a stream of <a href="https://www.ietf.org/rfc/rfc2046.txt">RFC 2046</a> multipart body parts. Callers read
 * parts one-at-a-time until {@link #nextPart()} returns null. After calling {@link #nextPart()} any preceding parts
 * should not be read.
 * <p>
 * Typical use loops over the parts in sequence:
 * <pre>
 * {@code
 * ClientResponse response = call.execute();
 * try (MultipartReader multipartReader = MultipartReader.create(response.getBody())) {
 *   while (true) {
 *     MultipartReader.Part part = multipartReader.nextPart();
 *     if (part == null) {
 *         break;
 *     }
 *     process(part.getHeaders(), part.getBody());
 *   }
 * }
 * }
 * </pre>
 * Note that {@link #nextPart()} will skip any unprocessed data from the preceding part. If the preceding part is
 * particularly large or if the underlying source is particularly slow, the {@link #nextPart()} call may be slow!
 * <p>
 * Closing a part <b>does not</b> close this multipart reader; callers must explicitly close this with {@link #close()}.
 */
public sealed interface MultipartReader extends AutoCloseable permits RealMultipartReader {
    static @NonNull MultipartReader create(final @NonNull ClientResponseBody responseBody) {
        Objects.requireNonNull(responseBody);
        return new RealMultipartReader(responseBody);
    }

    @NonNull String getBoundary();

    /**
     * @return the next part of this stream of multipart body parts, or null if there are no more parts.
     * @throws jayo.JayoException an IO Exception.
     */
    @Nullable Part nextPart();

    @Override
    void close();

    /**
     * A single part in a multipart body.
     */
    sealed interface Part extends AutoCloseable permits RealMultipartReader.Part {
        @NonNull Headers getHeaders();

        @NonNull Reader getBody();

        @Override
        void close();
    }
}
