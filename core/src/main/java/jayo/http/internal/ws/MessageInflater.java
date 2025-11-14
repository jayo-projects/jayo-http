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
import jayo.InflaterRawReader;
import jayo.Jayo;
import org.jspecify.annotations.NonNull;

import java.util.zip.Inflater;

final class MessageInflater implements AutoCloseable {
    private static final int OCTETS_TO_ADD_BEFORE_INFLATION = 0x0000ffff;

    private final boolean noContextTakeover;

    private final @NonNull Buffer deflatedBytes = Buffer.create();

    // Lazily-created.
    private Inflater inflater = null;
    private InflaterRawReader inflaterRawReader = null;

    MessageInflater(final boolean noContextTakeover) {
        this.noContextTakeover = noContextTakeover;
    }

    /**
     * Inflates {@code buffer} in place as described in RFC 7692 section 7.2.2.
     */
    void inflate(final @NonNull Buffer buffer) {
        assert buffer != null;

        if (deflatedBytes.bytesAvailable() != 0L) {
            throw new IllegalArgumentException();
        }

        if (inflater == null) {
            inflater = new Inflater(true);
        }

        if (inflaterRawReader == null) {
            inflaterRawReader = Jayo.inflate(deflatedBytes, inflater);
        }

        if (noContextTakeover) {
            inflater.reset();
        }

        deflatedBytes.writeAllFrom(buffer);
        deflatedBytes.writeInt(OCTETS_TO_ADD_BEFORE_INFLATION);

        long totalBytesToRead = inflater.getBytesRead() + deflatedBytes.bytesAvailable();

        // We cannot read all, as the source does not close.
        // Instead, we ensure that inflater has processed all bytes from the source.
        do {
            inflaterRawReader.readOrInflateAtMostTo(buffer, Long.MAX_VALUE);
        } while (inflater.getBytesRead() < totalBytesToRead && !inflater.finished());

        // The inflater data was self-terminated, and there's unexpected trailing data. Tear it all down so we don't
        // leak that data into the input of the next message.
        if (inflater.getBytesRead() < totalBytesToRead) {
            deflatedBytes.clear();
            inflaterRawReader.close();
            this.inflaterRawReader = null;
            this.inflater = null;
        }
    }

    @Override
    public void close() {
        if (inflaterRawReader != null) {
            inflaterRawReader.close();
        }
        inflaterRawReader = null;
        inflater = null;
    }
}
