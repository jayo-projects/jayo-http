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

import jayo.*;
import jayo.bytestring.ByteString;
import jayo.http.internal.Utils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import static jayo.http.internal.ws.WebSocketProtocol.*;

/**
 * An <a href="https://tools.ietf.org/html/rfc6455">RFC 6455</a>-compatible WebSocket frame reader.
 * <p>
 * This class is not thread safe.
 */
final class WebSocketReader implements AutoCloseable {
    private final boolean isClient;
    final @NonNull Reader reader;
    private final @NonNull FrameCallback frameCallback;
    private final boolean perMessageDeflate;
    private final boolean noContextTakeover;

    private boolean closed = false;
    private boolean receivedCloseFrame = false;

    // Stateful data about the current frame.
    private int opcode = 0;
    private long frameLength = 0L;
    private boolean isFinalFrame = false;
    private boolean isControlFrame = false;
    private boolean readingCompressedMessage = false;

    private final @NonNull Buffer controlFrameBuffer = Buffer.create();
    private final @NonNull Buffer messageFrameBuffer = Buffer.create();

    // Lazily initialized on first use.
    private MessageInflater messageInflater = null;

    // Masks are only a concern for server writers.
    private final byte @Nullable [] maskKey;
    private final Buffer.@Nullable UnsafeCursor maskCursor;

    WebSocketReader(final boolean isClient,
                    final @NonNull Reader reader,
                    final @NonNull FrameCallback frameCallback,
                    final boolean perMessageDeflate,
                    final boolean noContextTakeover) {
        assert reader != null;
        assert frameCallback != null;

        this.isClient = isClient;
        this.reader = reader;
        this.frameCallback = frameCallback;
        this.perMessageDeflate = perMessageDeflate;
        this.noContextTakeover = noContextTakeover;

        maskKey = isClient ? null : new byte[4];
        maskCursor = isClient ? null : Buffer.UnsafeCursor.create();
    }

    public interface FrameCallback {
        void onReadMessage(final @NonNull String text);

        void onReadMessage(final @NonNull ByteString bytes);

        void onReadPing(final @NonNull ByteString payload);

        void onReadPong(final @NonNull ByteString payload);

        void onReadClose(final int code, final @NonNull String reason);
    }

    /**
     * Process the next protocol frame.
     * <ul>
     * <li>If it is a control frame, this will result in a single call to {@link FrameCallback}.
     * <li>If it is a message frame, this will result in a single call to {@link FrameCallback#onReadMessage(String)} or
     * {@link FrameCallback#onReadMessage(ByteString)}. If the message spans multiple frames, each interleaved control
     * frame will result in a corresponding call to {@link FrameCallback}.
     * </ul>
     */
    void processNextFrame() {
        if (closed) {
            throw new JayoClosedResourceException();
        }

        readHeader();
        if (isControlFrame) {
            readControlFrame();
        } else {
            readMessageFrame();
        }
    }

    private void readHeader() {
        if (receivedCloseFrame) {
            throw new JayoException("Received close frame");
        }

        // Disable the timeout to read the first byte of a new frame.
        final var b0 = Cancellable.call(cancelScope -> {
            cancelScope.shield();
            return reader.readByte() & 0xff;
        });

        opcode = b0 & B0_MASK_OPCODE;
        isFinalFrame = (b0 & B0_FLAG_FIN) != 0;
        isControlFrame = (b0 & OPCODE_FLAG_CONTROL) != 0;

        // Control frames must be final frames (cannot contain continuations).
        if (isControlFrame && !isFinalFrame) {
            throw new JayoProtocolException("Control frames must be final.");
        }

        final var reservedFlag1 = (b0 & B0_FLAG_RSV1) != 0;
        switch (opcode) {
            case OPCODE_TEXT, OPCODE_BINARY -> {
                if (reservedFlag1) {
                    if (!perMessageDeflate) {
                        throw new JayoProtocolException("Unexpected rsv1 flag");
                    }
                    readingCompressedMessage = true;
                } else {
                    readingCompressedMessage = false;
                }
            }
            default -> {
                if (reservedFlag1) {
                    throw new JayoProtocolException("Unexpected rsv1 flag");
                }
            }
        }

        final var reservedFlag2 = (b0 & B0_FLAG_RSV2) != 0;
        if (reservedFlag2) {
            throw new JayoProtocolException("Unexpected rsv2 flag");
        }

        final var reservedFlag3 = (b0 & B0_FLAG_RSV3) != 0;
        if (reservedFlag3) {
            throw new JayoProtocolException("Unexpected rsv3 flag");
        }

        final var b1 = reader.readByte() & 0xff;

        final var isMasked = (b1 & B1_FLAG_MASK) != 0;
        if (isMasked == isClient) {
            // Masked payloads must be read on the server. Unmasked payloads must be read on the client.
            throw new JayoProtocolException(
                    isClient
                            ? "Server-sent frames must not be masked."
                            : "Client-sent frames must be masked.");
        }

        // Get frame length, optionally reading from follow-up bytes if indicated by special values.
        frameLength = (b1 & B1_MASK_LENGTH);
        if (frameLength == PAYLOAD_SHORT) {
            frameLength = reader.readShort() & 0xffff; // Value is unsigned.
        } else if (frameLength == PAYLOAD_LONG) {
            frameLength = reader.readLong();
            if (frameLength < 0L) {
                throw new JayoProtocolException(
                        "Frame length 0x" + Long.toHexString(frameLength) + " > 0x7FFFFFFFFFFFFFFF");
            }
        }

        if (isControlFrame && frameLength > PAYLOAD_BYTE_MAX) {
            throw new JayoProtocolException("Control frame must be less than " + PAYLOAD_BYTE_MAX + "B.");
        }

        if (isMasked) {
            // Read the masking key as bytes so that they can be used directly for unmasking.
            assert maskKey != null;
            reader.readTo(maskKey);
        }
    }

    private void readControlFrame() {
        if (frameLength > 0L) {
            reader.readTo(controlFrameBuffer, frameLength);

            if (!isClient) {
                assert maskCursor != null;
                controlFrameBuffer.readAndWriteUnsafe(maskCursor);
                maskCursor.seek(0);
                assert maskKey != null;
                toggleMask(maskCursor, maskKey);
                maskCursor.close();
            }
        }

        switch (opcode) {
            case OPCODE_CONTROL_PING -> frameCallback.onReadPing(controlFrameBuffer.readByteString());
            case OPCODE_CONTROL_PONG -> frameCallback.onReadPong(controlFrameBuffer.readByteString());
            case OPCODE_CONTROL_CLOSE -> {
                var code = CLOSE_NO_STATUS_CODE;
                var reason = "";
                final var bufferSize = controlFrameBuffer.bytesAvailable();
                if (bufferSize == 1L) {
                    throw new JayoProtocolException("Malformed close payload length of 1.");
                } else if (bufferSize != 0L) {
                    code = controlFrameBuffer.readShort();
                    reason = controlFrameBuffer.readString();
                    final var codeExceptionMessage = WebSocketProtocol.closeCodeExceptionMessage(code);
                    if (codeExceptionMessage != null) {
                        throw new JayoProtocolException(codeExceptionMessage);
                    }
                }
                frameCallback.onReadClose(code, reason);
                receivedCloseFrame = true;
            }
            default -> throw new JayoProtocolException("Unknown control opcode: " + Integer.toHexString(opcode));
        }
    }

    private void readMessageFrame() {
        int opcode = this.opcode;
        if (opcode != OPCODE_TEXT && opcode != OPCODE_BINARY) {
            throw new JayoProtocolException("Unknown opcode: " + Integer.toHexString(opcode));
        }

        readMessage();

        if (readingCompressedMessage) {
            if (messageInflater == null) {
                messageInflater = new MessageInflater(noContextTakeover);
            }
            messageInflater.inflate(messageFrameBuffer);
        }

        if (opcode == OPCODE_TEXT) {
            frameCallback.onReadMessage(messageFrameBuffer.readString());
        } else {
            frameCallback.onReadMessage(messageFrameBuffer.readByteString());
        }
    }

    /**
     * Reads a message body into across one or more frames. Control frames that occur between fragments will be
     * processed. If the message payload is masked, this will unmask as it's being processed.
     */
    private void readMessage() {
        while (true) {
            if (receivedCloseFrame) {
                throw new JayoException("Received close frame");
            }

            if (frameLength > 0L) {
                reader.readTo(messageFrameBuffer, frameLength);

                if (!isClient) {
                    assert maskCursor != null;
                    messageFrameBuffer.readAndWriteUnsafe(maskCursor);
                    maskCursor.seek(messageFrameBuffer.bytesAvailable() - frameLength);
                    assert maskKey != null;
                    toggleMask(maskCursor, maskKey);
                    maskCursor.close();
                }
            }

            if (isFinalFrame) {
                break; // We are exhausted and have no continuations.
            }

            readUntilNonControlFrame();
            if (opcode != OPCODE_CONTINUATION) {
                throw new JayoProtocolException("Expected continuation opcode. Got: " + Integer.toHexString(opcode));
            }
        }
    }

    /**
     * Read headers and process any control frames until we reach a non-control frame.
     */
    private void readUntilNonControlFrame() {
        while (!receivedCloseFrame) {
            readHeader();
            if (!isControlFrame) {
                break;
            }
            readControlFrame();
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (messageInflater != null) {
            Utils.closeQuietly(messageInflater);
        }
        Utils.closeQuietly(reader);
    }
}
