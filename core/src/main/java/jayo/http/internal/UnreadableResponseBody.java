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
import jayo.Jayo;
import jayo.RawReader;
import jayo.Reader;
import jayo.http.ClientResponseBody;
import jayo.http.MediaType;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public final class UnreadableResponseBody extends ClientResponseBody implements RawReader {
    private final MediaType mediaType;
    private final long contentLength;

    UnreadableResponseBody(final @Nullable MediaType mediaType, final long contentLength) {
        this.mediaType = mediaType;
        this.contentLength = contentLength;
    }

    @Override
    public @Nullable MediaType contentType() {
        return mediaType;
    }

    @Override
    public long contentByteSize() {
        return contentLength;
    }

    @Override
    public @NonNull Reader reader() {
        return Jayo.buffer(this);
    }

    @Override
    public long readAtMostTo(final @NonNull Buffer writer, final long byteCount) {
        assert writer != null;
        throw new IllegalStateException(
                """
                        Unreadable ResponseBody! These Response objects have bodies that are stripped:
                         * ClientResponse.cacheResponse
                         * ClientResponse.networkResponse
                         * ClientResponse.priorResponse
                         * EventSourceListener
                         * WebSocketListener
                        (It is safe to call contentType() and contentByteSize() on these response bodies.)
                        """.stripIndent()
        );
    }

    @Override
    public void close() {
    }
}
