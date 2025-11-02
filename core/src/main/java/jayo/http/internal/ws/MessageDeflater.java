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

package jayo.http.internal.ws;

import jayo.Buffer;
import jayo.Jayo;
import jayo.RawWriter;
import jayo.bytestring.ByteString;
import org.jspecify.annotations.NonNull;

import java.io.Closeable;
import java.util.zip.Deflater;

final class MessageDeflater implements Closeable {
    private final @NonNull ByteString EMPTY_DEFLATE_BLOCK = ByteString.decodeHex("000000ffff");
    private static final int LAST_OCTETS_COUNT_TO_REMOVE_AFTER_DEFLATION = 4;

    private final boolean noContextTakeover;

    private final @NonNull Buffer deflatedBytes = Buffer.create();

    private final @NonNull Deflater deflater = new Deflater(
            Deflater.DEFAULT_COMPRESSION,
            true); // nowrap (omits zlib header)

    private final @NonNull RawWriter deflaterRawWriter = Jayo.deflate(deflatedBytes, deflater);

    MessageDeflater(final boolean noContextTakeover) {
        this.noContextTakeover = noContextTakeover;
    }

    void deflate(final @NonNull Buffer buffer) {
        if (deflatedBytes.bytesAvailable() != 0L) {
            throw new IllegalArgumentException();
        }

        if (noContextTakeover) {
            deflater.reset();
        }

        deflaterRawWriter.writeFrom(buffer, buffer.bytesAvailable());
        deflaterRawWriter.flush();

        if (endsWith(deflatedBytes, EMPTY_DEFLATE_BLOCK)) {
            final var newSize = deflatedBytes.bytesAvailable() - LAST_OCTETS_COUNT_TO_REMOVE_AFTER_DEFLATION;
            try (final var cursor = deflatedBytes.readAndWriteUnsafe()) {
                cursor.resizeBuffer(newSize);
            }
        } else {
            // Same as adding EMPTY_DEFLATE_BLOCK and then removing 4 bytes.
            deflatedBytes.writeByte((byte) 0x00);
        }

        buffer.writeFrom(deflatedBytes, deflatedBytes.bytesAvailable());
    }

    private static boolean endsWith(final @NonNull Buffer buffer, final @NonNull ByteString suffix) {
        return buffer.rangeEquals(buffer.bytesAvailable() - suffix.byteSize(), suffix);
    }

    @Override
    public void close() {
        deflaterRawWriter.close();
    }
}
