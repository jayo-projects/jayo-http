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

package jayo.http.internal.http2;

import jayo.Buffer;
import jayo.JayoClosedResourceException;
import jayo.Writer;
import jayo.http.http2.ErrorCode;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.io.Closeable;
import java.io.Flushable;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.lang.System.Logger.Level.TRACE;
import static jayo.http.internal.Utils.format;
import static jayo.http.internal.http2.Http2.*;

/**
 * Writes HTTP/2 transport frames.
 */
final class Http2Writer implements Closeable, Flushable {
    private static final System.Logger LOGGER = System.getLogger("jayo.http.Http2");

    private final @NonNull Writer writer;
    private final boolean client;

    private final @NonNull Buffer hpackBuffer = Buffer.create();
    private int maxFrameSize = INITIAL_MAX_FRAME_SIZE;
    private boolean closed = false;
    final Hpack.@NonNull Writer hpackWriter = new Hpack.Writer(hpackBuffer);

    final @NonNull Lock lock = new ReentrantLock();

    Http2Writer(final @NonNull Writer writer, final boolean client) {
        assert writer != null;

        this.writer = writer;
        this.client = client;
    }

    void connectionPreface() {
        lock.lock();
        try {
            if (closed) {
                throw new JayoClosedResourceException();
            }
            if (!client) {
                return; // Nothing to write; servers don't send connection headers!
            }
            if (LOGGER.isLoggable(TRACE)) {
                LOGGER.log(TRACE, format(">> CONNECTION %s", CONNECTION_PREFACE.hex()));
            }
            writer.write(CONNECTION_PREFACE);
            writer.flush();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Applies {@code peerSettings} and then sends a settings ACK.
     */
    void applyAndAckSettings(final @NonNull Settings peerSettings) {
        assert peerSettings != null;

        lock.lock();
        try {
            if (closed) {
                throw new JayoClosedResourceException();
            }
            this.maxFrameSize = peerSettings.maxFrameSize(maxFrameSize);
            if (peerSettings.headerTableSize() != -1) {
                hpackWriter.resizeHeaderTable(peerSettings.headerTableSize());
            }
            frameHeader(
                    0, // streamId
                    0, // length
                    TYPE_SETTINGS, // type
                    FLAG_ACK // flags
            );
            writer.flush();
        } finally {
            lock.unlock();
        }
    }

    /**
     * HTTP/2 only. Send a push promise header block.
     * <p>
     * A push promise contains all the headers that pertain to a server-initiated request, and a
     * {@code promisedStreamId} to which response frames will be delivered. Push promise frames are sent as a part of
     * the response to {@code streamId}. The {@code promisedStreamId} has a priority of one greater than
     * {@code streamId}.
     *
     * @param streamId         client-initiated stream ID.  Must be an odd number.
     * @param promisedStreamId server-initiated stream ID.  Must be an even number.
     * @param requestHeaders   minimally includes {@code :method}, {@code :scheme}, {@code :authority}, and {@code :path}.
     */
    void pushPromise(final int streamId,
                     final int promisedStreamId,
                     final @NonNull List<@NonNull RealBinaryHeader> requestHeaders) {
        assert requestHeaders != null;

        lock.lock();
        try {
            if (closed) {
                throw new JayoClosedResourceException();
            }
            hpackWriter.writeHeaders(requestHeaders);

            final var byteCount = hpackBuffer.bytesAvailable();
            final var length = (int) Math.min(maxFrameSize - 4L, byteCount);
            frameHeader(
                    streamId,
                    length + 4,
                    TYPE_PUSH_PROMISE,
                    (byteCount == (long) length) ? FLAG_END_HEADERS : 0
            );
            writer.writeInt(promisedStreamId & 0x7fffffff);
            writer.writeFrom(hpackBuffer, length);

            if (byteCount > length) {
                writeContinuationFrames(streamId, byteCount - length);
            }
        } finally {
            lock.unlock();
        }
    }

    void headers(final boolean outFinished,
                 final int streamId,
                 final @NonNull List<@NonNull RealBinaryHeader> headerBlock) {
        assert headerBlock != null;

        lock.lock();
        try {
            if (closed) {
                throw new JayoClosedResourceException();
            }
            hpackWriter.writeHeaders(headerBlock);

            final var byteCount = hpackBuffer.bytesAvailable();
            final var length = Math.min(maxFrameSize, byteCount);
            var flags = (byteCount == length) ? FLAG_END_HEADERS : 0;
            if (outFinished) {
                flags |= FLAG_END_STREAM;
            }
            frameHeader(
                    streamId,
                    (int) length,
                    TYPE_HEADERS,
                    flags
            );
            writer.writeFrom(hpackBuffer, length);

            if (byteCount > length) {
                writeContinuationFrames(streamId, byteCount - length);
            }
        } finally {
            lock.unlock();
        }
    }

    private void writeContinuationFrames(final int streamId, final long byteCount) {
        var remaining = byteCount;
        while (remaining > 0L) {
            final var length = Math.min(maxFrameSize, remaining);
            remaining -= length;
            frameHeader(
                    streamId,
                    (int) length,
                    TYPE_CONTINUATION,
                    (remaining == 0L) ? FLAG_END_HEADERS : 0
            );
            writer.writeFrom(hpackBuffer, length);
        }
    }

    void rstStream(final int streamId, final @NonNull ErrorCode errorCode) {
        assert errorCode != null;

        lock.lock();
        try {
            if (closed) {
                throw new JayoClosedResourceException();
            }
            if (errorCode.getHttpCode() == -1) {
                throw new IllegalArgumentException();
            }

            frameHeader(
                    streamId,
                    4,
                    TYPE_RST_STREAM,
                    FLAG_NONE
            );
            writer.writeInt(errorCode.getHttpCode());
            writer.flush();
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return the maximum size of bytes that may be sent in a single call to {@link #data(boolean, int, Buffer, int)}.
     */
    public int maxDataLength() {
        return maxFrameSize;
    }

    /**
     * {@code source.bytesAvailable()} may be longer than the max length of the variant's data frame. Implementations
     * must send multiple frames as necessary.
     *
     * @param source    the buffer to draw bytes from. May be null if byteCount is 0.
     * @param byteCount must be between 0 and the minimum of {@code source.bytesAvailable()} and {@link #maxDataLength}.
     */
    void data(final boolean outFinished,
              final int streamId,
              final @Nullable Buffer source,
              final int byteCount) {
        lock.lock();
        try {
            if (closed) {
                throw new JayoClosedResourceException();
            }
            var flags = FLAG_NONE;
            if (outFinished) {
                flags = flags | FLAG_END_STREAM;
            }
            dataFrame(streamId, flags, source, byteCount);
        } finally {
            lock.unlock();
        }
    }

    void dataFrame(final int streamId,
                   final int flags,
                   final @Nullable Buffer buffer,
                   final int byteCount) {
        frameHeader(
                streamId,
                byteCount,
                TYPE_DATA,
                flags
        );
        if (byteCount > 0) {
            assert buffer != null;
            writer.writeFrom(buffer, byteCount);
        }
    }

    /**
     * Write Jayo HTTP's settings to the peer.
     */
    void settings(Settings settings) {
        lock.lock();
        try {
            if (closed) {
                throw new JayoClosedResourceException();
            }
            frameHeader(
                    0, // streamId
                    settings.size() * 6, // length
                    TYPE_SETTINGS, // type
                    FLAG_NONE // flags
            );
            for (var i = 0; i < Settings.COUNT; i++) {
                if (!settings.isSet(i)) {
                    continue;
                }
                writer.writeShort((short) i);
                writer.writeInt(settings.get(i));
            }
            writer.flush();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Send a connection-level ping to the peer. {@code ack} indicates this is a reply. The data in {@code payload1} and
     * {@code payload2} opaque binary, and there are no rules on the content.
     */
    void ping(final boolean ack,
              final int payload1,
              final int payload2) {
        lock.lock();
        try {
            if (closed) {
                throw new JayoClosedResourceException();
            }
            frameHeader(
                    0,
                    8,
                    TYPE_PING,
                    ack ? FLAG_ACK : FLAG_NONE
            );
            writer.writeInt(payload1);
            writer.writeInt(payload2);
            writer.flush();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Tell the peer to stop creating streams and that we last processed {@code lastGoodStreamId}, or zero if no streams
     * were processed.
     *
     * @param lastGoodStreamId the last stream ID processed, or zero if no streams were processed.
     * @param errorCode        reason for closing the connection.
     * @param debugData        only valid for HTTP/2; opaque debug data to send.
     */
    void goAway(final int lastGoodStreamId,
                final @NonNull ErrorCode errorCode,
                final byte @NonNull [] debugData) {
        assert errorCode != null;
        assert debugData != null;

        lock.lock();
        try {
            if (closed) {
                throw new JayoClosedResourceException();
            }
            if (errorCode.getHttpCode() == -1) {
                throw new IllegalArgumentException("errorCode.httpCode == -1");
            }
            frameHeader(
                    0,
                    8 + debugData.length,
                    TYPE_GOAWAY,
                    FLAG_NONE
            );
            writer.writeInt(lastGoodStreamId);
            writer.writeInt(errorCode.getHttpCode());
            if (debugData.length > 0) {
                writer.write(debugData);
            }
            writer.flush();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inform peer that an additional {@code windowSizeIncrement} bytes can be sent on {@code streamId}, or the
     * connection if {@code streamId} is zero.
     */
    void windowUpdate(final int streamId, final long windowSizeIncrement) {
        lock.lock();
        try {
            if (closed) {
                throw new JayoClosedResourceException();
            }
            if (!(windowSizeIncrement != 0L && windowSizeIncrement <= 0x7fffffffL)) {
                throw new IllegalArgumentException(
                        "windowSizeIncrement == 0 || windowSizeIncrement > 0x7fffffffL: " + windowSizeIncrement);
            }
            if (LOGGER.isLoggable(TRACE)) {
                LOGGER.log(TRACE,
                        frameLogWindowUpdate(
                                false,
                                streamId,
                                4,
                                windowSizeIncrement
                        ));
            }
            frameHeader(
                    streamId,
                    4,
                    TYPE_WINDOW_UPDATE,
                    FLAG_NONE
            );
            writer.writeInt((int) windowSizeIncrement);
            writer.flush();
        } finally {
            lock.unlock();
        }
    }

    void frameHeader(final int streamId,
                     final int length,
                     final int type,
                     final int flags) {
        if (type != TYPE_WINDOW_UPDATE && LOGGER.isLoggable(TRACE)) {
            LOGGER.log(TRACE, frameLog(false, streamId, length, type, flags));
        }
        if (!(length <= maxFrameSize)) {
            throw new IllegalArgumentException("FRAME_SIZE_ERROR length > " + maxFrameSize + ": " + length);
        }
        if ((streamId & 0x80000000) != 0) {
            throw new IllegalArgumentException("reserved bit set: " + streamId);
        }
        writeMedium(writer, length);
        writer.writeByte((byte) (type & 0xff));
        writer.writeByte((byte) (flags & 0xff));
        writer.writeInt(streamId & 0x7fffffff);
    }

    private static void writeMedium(final @NonNull Writer writer, final int medium) {
        assert writer != null;

        writer.writeByte((byte) ((medium >>> 16) & 0xff));
        writer.writeByte((byte) ((medium >>> 8) & 0xff));
        writer.writeByte((byte) (medium & 0xff));
    }

    @Override
    public void flush() {
        lock.lock();
        try {
            if (closed) {
                throw new JayoClosedResourceException();
            }
            writer.flush();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            closed = true;
            writer.close();
        } finally {
            lock.unlock();
        }
    }
}
