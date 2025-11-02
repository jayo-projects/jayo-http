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
import jayo.JayoClosedResourceException;
import jayo.Writer;
import jayo.bytestring.ByteString;
import jayo.http.internal.Utils;
import jayo.tools.JayoUtils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.Closeable;
import java.util.Random;

import static jayo.http.internal.ws.WebSocketProtocol.*;

/**
 * An <a href="https://tools.ietf.org/html/rfc6455">RFC 6455</a>-compatible WebSocket frame writer.
 * <p>
 * This class is not thread safe.
 */
final class WebSocketWriter implements Closeable {
    private final boolean isClient;
    final @NonNull Writer writer;
    final @NonNull Random random;
    private final boolean perMessageDeflate;
    private final boolean noContextTakeover;
    private final long minimumDeflateSize;

    /**
     * This holds outbound data for compression and masking.
     */
    private final @NonNull Buffer messageBuffer = Buffer.create();

    /**
     * The {@link Buffer} of {@link #writer}. Write to this and then flush/emit {@link #writer} in a controlled manner.
     */
    private final @NonNull Buffer sinkBuffer;
    private boolean writerClosed = false;

    /**
     * Lazily initialized on first use.
     */
    private MessageDeflater messageDeflater = null;

    // Masks are only a concern for client writers.
    private final byte @Nullable [] maskKey;
    private final Buffer.@Nullable UnsafeCursor maskCursor;

    WebSocketWriter(final boolean isClient,
                    final @NonNull Writer writer,
                    final @NonNull Random random,
                    final boolean perMessageDeflate,
                    final boolean noContextTakeover,
                    final long minimumDeflateSize) {
        assert writer != null;
        assert random != null;

        this.isClient = isClient;
        this.writer = writer;
        this.random = random;
        this.perMessageDeflate = perMessageDeflate;
        this.noContextTakeover = noContextTakeover;
        this.minimumDeflateSize = minimumDeflateSize;

        sinkBuffer = JayoUtils.buffer(writer);
        maskKey = isClient ? new byte[4] : null;
        maskCursor = isClient ? Buffer.UnsafeCursor.create() : null;
    }

    /**
     * Send a ping with the supplied {@code payload}.
     */
    public void writePing(final @NonNull ByteString payload) {
        writeControlFrame(OPCODE_CONTROL_PING, payload);
    }

    /**
     * Send a pong with the supplied {@code payload}.
     */
    void writePong(final @NonNull ByteString payload) {
        writeControlFrame(OPCODE_CONTROL_PONG, payload);
    }

    /**
     * Send a close frame with optional code and reason.
     *
     * @param code   Status code as defined by
     *               <a href="https://tools.ietf.org/html/rfc6455#section-7.4">Section 7.4 of RFC 6455</a> or {@code 0}.
     * @param reason Reason for shutting down or {@code null}.
     */
    public void writeClose(final short code, final @Nullable ByteString reason) {
        var payload = ByteString.EMPTY;
        if (code != 0 || reason != null) {
            if (code != 0) {
                validateCloseCode(code);
            }
            payload = Buffer.create()
                    .writeShort(code)
                    .write((reason != null) ? reason : ByteString.EMPTY)
                    .readByteString();
        }

        try {
            writeControlFrame(OPCODE_CONTROL_CLOSE, payload);
        } finally {
            writerClosed = true;
        }
    }

    private void writeControlFrame(final byte opcode, final @NonNull ByteString payload) {
        assert payload != null;
        if (writerClosed) {
            throw new JayoClosedResourceException();
        }

        final var length = payload.byteSize();
        if (length > PAYLOAD_BYTE_MAX) {
            throw new IllegalArgumentException("Payload size must be less than or equal to " + PAYLOAD_BYTE_MAX);
        }

        final var b0 = B0_FLAG_FIN | opcode;
        sinkBuffer.writeByte((byte) b0);

        var b1 = length;
        if (isClient) {
            b1 |= B1_FLAG_MASK;
            sinkBuffer.writeByte((byte) b1);

            assert maskKey != null;
            random.nextBytes(maskKey);
            sinkBuffer.write(maskKey);

            if (length > 0) {
                final var payloadStart = sinkBuffer.bytesAvailable();
                sinkBuffer.write(payload);

                assert maskCursor != null;
                sinkBuffer.readAndWriteUnsafe(maskCursor);
                maskCursor.seek(payloadStart);
                toggleMask(maskCursor, maskKey);
                maskCursor.close();
            }
        } else {
            sinkBuffer.writeByte((byte) b1);
            sinkBuffer.write(payload);
        }

        writer.flush();
    }

    void writeMessageFrame(final byte formatOpcode,
                           final @NonNull ByteString data) {
        assert data != null;
        if (writerClosed) {
            throw new JayoClosedResourceException();
        }

        messageBuffer.write(data);

        var b0 = formatOpcode | B0_FLAG_FIN;

        if (perMessageDeflate && data.byteSize() >= minimumDeflateSize) {
            if (messageDeflater == null) {
                messageDeflater = new MessageDeflater(noContextTakeover);
            }
            messageDeflater.deflate(messageBuffer);
            b0 |= B0_FLAG_RSV1;
        }

        // val dataSize = messageBuffer.size
        final var dataSize = messageBuffer.bytesAvailable();

        sinkBuffer.writeByte((byte) b0);

        var b1 = 0;

        if (isClient) {
            b1 |= B1_FLAG_MASK;
        }

        if (dataSize <= PAYLOAD_BYTE_MAX) {
            b1 |= (int) dataSize;
            sinkBuffer.writeByte((byte) b1);
        } else if (dataSize <= PAYLOAD_SHORT_MAX) {
            b1 |= PAYLOAD_SHORT;
            sinkBuffer.writeByte((byte) b1);
            sinkBuffer.writeShort((short) dataSize);
        } else {
            b1 |= PAYLOAD_LONG;
            sinkBuffer.writeByte((byte) b1);
            sinkBuffer.writeLong(dataSize);
        }

        if (isClient) {
            assert maskKey != null;
            random.nextBytes(maskKey);
            sinkBuffer.write(maskKey);

            if (dataSize > 0L) {
                assert maskCursor != null;
                messageBuffer.readAndWriteUnsafe(maskCursor);
                maskCursor.seek(0L);
                toggleMask(maskCursor, maskKey);
                maskCursor.close();
            }
        }

        sinkBuffer.writeFrom(messageBuffer, dataSize);
        writer.flush();
    }

    @Override
    public void close() {
        if (messageDeflater != null) {
            Utils.closeQuietly(messageDeflater);
        }
        Utils.closeQuietly(writer);
    }
}
