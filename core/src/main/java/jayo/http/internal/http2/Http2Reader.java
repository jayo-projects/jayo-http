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

import jayo.*;
import jayo.bytestring.ByteString;
import jayo.http.http2.ErrorCode;
import org.jspecify.annotations.NonNull;

import java.io.Closeable;
import java.util.List;

import static java.lang.System.Logger.Level.TRACE;
import static jayo.http.internal.Utils.format;
import static jayo.http.internal.http2.Http2.*;

/**
 * Reads HTTP/2 transport frames.
 * <p>
 * This implementation assumes we do not send an increased {@linkplain Settings#maxFrameSize(int) frame} to the peer.
 * Hence, we expect all frames to have a max length of {@link Http2#INITIAL_MAX_FRAME_SIZE}.
 */
final class Http2Reader implements Closeable {
    private static final System.Logger LOGGER = System.getLogger("jayo.http.Http2");

    private final @NonNull Reader reader;
    private final boolean client;


    private final @NonNull ContinuationRawReader continuation;
    private final Hpack.@NonNull Reader hpackReader;

    /**
     * Creates a frame reader with a max header table size of 4096.
     */
    Http2Reader(final @NonNull Reader reader, final boolean client) {
        assert reader != null;

        this.reader = reader;
        this.client = client;
        continuation = new ContinuationRawReader(reader);
        hpackReader = new Hpack.Reader(continuation, 4096, 4096);
    }

    void readConnectionPreface(final @NonNull Handler handler) {
        if (client) {
            // The client reads the initial SETTINGS frame.
            if (!nextFrame(true, handler)) {
                throw new JayoException("Required SETTINGS preface not received");
            }
        } else {
            // The server reads the CONNECTION_PREFACE byte string.
            final var connectionPreface = reader.readByteString(CONNECTION_PREFACE.byteSize());
            if (LOGGER.isLoggable(TRACE)) {
                LOGGER.log(TRACE, format("<< CONNECTION %s", connectionPreface.hex()));
            }
            if (!CONNECTION_PREFACE.equals(connectionPreface)) {
                throw new JayoException("Expected a connection header but was " + connectionPreface.decodeToString());
            }
        }
    }

    boolean nextFrame(final boolean requireSettings, final @NonNull Handler handler) {
        assert handler != null;

        try {
            reader.require(9); // Frame header size.
        } catch (JayoEOFException ignored) {
            return false; // This might be a normal socket close.
        }

        //  0                   1                   2                   3
        //  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        // |                 Length (24)                   |
        // +---------------+---------------+---------------+
        // |   Type (8)    |   Flags (8)   |
        // +-+-+-----------+---------------+-------------------------------+
        // |R|                 Stream Identifier (31)                      |
        // +=+=============================================================+
        // |                   Frame Payload (0...)                      ...
        // +---------------------------------------------------------------+
        final var length = readMedium(reader);
        if (length > INITIAL_MAX_FRAME_SIZE) {
            throw new JayoException("FRAME_SIZE_ERROR: " + length);
        }
        final var type = reader.readByte() & 0xff;
        final var flags = reader.readByte() & 0xff;
        final var streamId = reader.readInt() & 0x7fffffff; // Ignore reserved bit.
        if (type != TYPE_WINDOW_UPDATE && LOGGER.isLoggable(TRACE)) {
            LOGGER.log(TRACE, frameLog(true, streamId, length, type, flags));
        }

        if (requireSettings && type != TYPE_SETTINGS) {
            throw new JayoException("Expected a SETTINGS frame but was " + formattedType(type));
        }

        switch (type) {
            case TYPE_DATA -> readData(handler, length, flags, streamId);
            case TYPE_HEADERS -> readHeaders(handler, length, flags, streamId);
            case TYPE_PRIORITY -> readPriority(handler, length, streamId);
            case TYPE_RST_STREAM -> readRstStream(handler, length, streamId);
            case TYPE_SETTINGS -> readSettings(handler, length, flags, streamId);
            case TYPE_PUSH_PROMISE -> readPushPromise(handler, length, flags, streamId);
            case TYPE_PING -> readPing(handler, length, flags, streamId);
            case TYPE_GOAWAY -> readGoAway(handler, length, streamId);
            case TYPE_WINDOW_UPDATE -> readWindowUpdate(handler, length, flags, streamId);
            default -> reader.skip(length); // Implementations MUST discard frames of unknown types.
        }

        return true;
    }

    private void readHeaders(final @NonNull Handler handler,
                             final int length,
                             final int flags,
                             final int streamId) {
        assert handler != null;

        if (streamId == 0) {
            throw new JayoException("PROTOCOL_ERROR: TYPE_HEADERS streamId == 0");
        }

        final var endStream = (flags & FLAG_END_STREAM) != 0;
        final var padding = ((flags & FLAG_PADDED) != 0) ? reader.readByte() & 0xff : 0;

        var headerBlockLength = length;
        if ((flags & FLAG_PRIORITY) != 0) {
            readPriority(handler, streamId);
            headerBlockLength -= 5; // account for above read.
        }
        headerBlockLength = lengthWithoutPadding(headerBlockLength, flags, padding);
        final var headerBlock = readHeaderBlock(headerBlockLength, padding, flags, streamId);

        handler.headers(endStream, streamId, -1, headerBlock);
    }

    private @NonNull List<@NonNull RealBinaryHeader> readHeaderBlock(final int length,
                                                                     final int padding,
                                                                     final int flags,
                                                                     final int streamId) {
        continuation.left = length;
        continuation.length = continuation.left;
        continuation.padding = padding;
        continuation.flags = flags;
        continuation.streamId = streamId;

        // TODO: Concat multi-value headers with 0x0, except COOKIE, which uses 0x3B, 0x20.
        // http://tools.ietf.org/html/draft-ietf-httpbis-http2-17#section-8.1.2.5
        hpackReader.readHeaders();
        return hpackReader.getAndResetHeaderList();
    }

    private void readData(final @NonNull Handler handler,
                          final int length,
                          final int flags,
                          final int streamId) {
        assert handler != null;

        if (streamId == 0) {
            throw new JayoException("PROTOCOL_ERROR: TYPE_DATA streamId == 0");
        }

        // TODO: checkState open or half-closed (local) or raise STREAM_CLOSED
        final var inFinished = (flags & FLAG_END_STREAM) != 0;
        final var gzipped = (flags & FLAG_COMPRESSED) != 0;
        if (gzipped) {
            throw new JayoException("PROTOCOL_ERROR: FLAG_COMPRESSED without SETTINGS_COMPRESS_DATA");
        }

        final var padding = ((flags & FLAG_PADDED) != 0) ? reader.readByte() & 0xff : 0;
        final var dataLength = lengthWithoutPadding(length, flags, padding);

        handler.data(inFinished, streamId, reader, dataLength);
        reader.skip(padding);
    }

    private void readPriority(final @NonNull Handler handler,
                              final int length,
                              final int streamId) {
        assert handler != null;

        if (streamId == 0) {
            throw new JayoException("PROTOCOL_ERROR: TYPE_PRIORITY streamId == 0");
        }
        if (length != 5) {
            throw new JayoException("PROTOCOL_ERROR: TYPE_PRIORITY length: " + length + " != 5");
        }
        readPriority(handler, streamId);
    }

    private void readPriority(final @NonNull Handler handler, final int streamId) {
        assert handler != null;

        int w1 = reader.readInt();
        final var exclusive = (w1 & 0x80000000) != 0;
        final var streamDependency = w1 & 0x7fffffff;
        final var weight = (reader.readByte() & 0xff) + 1;
        handler.priority(streamId, streamDependency, weight, exclusive);
    }

    private void readRstStream(final @NonNull Handler handler,
                               final int length,
                               final int streamId) {
        if (streamId == 0) {
            throw new JayoException("PROTOCOL_ERROR: TYPE_RST_STREAM streamId == 0");
        }
        if (length != 4) {
            throw new JayoException("PROTOCOL_ERROR: TYPE_RST_STREAM length: " + length + " != 4");
        }
        final var errorCodeInt = reader.readInt();
        final var errorCode = ErrorCode.fromHttp2(errorCodeInt);
        if (errorCode == null) {
            throw new JayoException("TYPE_RST_STREAM unexpected error code: " + errorCodeInt);
        }
        handler.rstStream(streamId, errorCode);
    }

    private void readSettings(final @NonNull Handler handler,
                              final int length,
                              final int flags,
                              final int streamId) {
        assert handler != null;

        if (streamId != 0) {
            throw new JayoException("PROTOCOL_ERROR: TYPE_SETTINGS streamId != 0");
        }
        if ((flags & FLAG_ACK) != 0) {
            if (length != 0) {
                throw new JayoException("PROTOCOL_ERROR: FRAME_SIZE_ERROR ack frame should be empty!");
            }
            handler.ackSettings();
            return;
        }

        if (length % 6 != 0) {
            throw new JayoException("PROTOCOL_ERROR: TYPE_SETTINGS length % 6 != 0: " + length);
        }
        final var settings = new Settings();
        for (var i = 0; i < length; i += 6) {
            final var id = reader.readShort() & 0xffff;
            final var value = reader.readInt();

            switch (id) {
                // SETTINGS_HEADER_TABLE_SIZE
                case 1 -> {
                }

                // SETTINGS_ENABLE_PUSH
                case 2 -> {
                    if (value != 0 && value != 1) {
                        throw new JayoException("PROTOCOL_ERROR: SETTINGS_ENABLE_PUSH != 0 or 1");
                    }
                }

                // SETTINGS_MAX_CONCURRENT_STREAMS
                case 3 -> {
                }

                // SETTINGS_INITIAL_WINDOW_SIZE
                case 4 -> {
                    if (value < 0) {
                        throw new JayoException("PROTOCOL_ERROR: SETTINGS_INITIAL_WINDOW_SIZE > 2^31 - 1");
                    }
                }

                // SETTINGS_MAX_FRAME_SIZE
                case 5 -> {
                    if (value < INITIAL_MAX_FRAME_SIZE || value > 16777215) {
                        throw new JayoException("PROTOCOL_ERROR: SETTINGS_MAX_FRAME_SIZE: " + value);
                    }
                }

                // SETTINGS_MAX_HEADER_LIST_SIZE
                case 6 -> { // Advisory only, so ignored.
                }

                // Must ignore setting with unknown id.
                default -> {
                }
            }
            settings.set(id, value);
        }
        handler.settings(false, settings);
    }

    private void readPushPromise(final @NonNull Handler handler,
                                 final int length,
                                 final int flags,
                                 final int streamId) {
        assert handler != null;

        if (streamId == 0) {
            throw new JayoException("PROTOCOL_ERROR: TYPE_PUSH_PROMISE streamId == 0");
        }
        final var padding = ((flags & FLAG_PADDED) != 0) ? reader.readByte() & 0xff : 0;
        final var promisedStreamId = reader.readInt() & 0x7fffffff;
        final var headerBlockLength = lengthWithoutPadding(length - 4, flags, padding); // - 4 for readInt().
        final var headerBlock = readHeaderBlock(headerBlockLength, padding, flags, streamId);
        handler.pushPromise(streamId, promisedStreamId, headerBlock);
    }

    private void readPing(final @NonNull Handler handler,
                          final int length,
                          final int flags,
                          final int streamId) {
        assert handler != null;

        if (streamId != 0) {
            throw new JayoException("PROTOCOL_ERROR: TYPE_PING streamId != 0");
        }
        if (length != 8) {
            throw new JayoException("PROTOCOL_ERROR: TYPE_PING length != 8: " + length);
        }
        final var payload1 = reader.readInt();
        final var payload2 = reader.readInt();
        final var ack = (flags & FLAG_ACK) != 0;
        handler.ping(ack, payload1, payload2);
    }

    private void readGoAway(final @NonNull Handler handler,
                            final int length,
                            final int streamId) {
        assert handler != null;

        if (streamId != 0) {
            throw new JayoException("PROTOCOL_ERROR: TYPE_GOAWAY streamId != 0");
        }
        if (length < 8) {
            throw new JayoException("PROTOCOL_ERROR: TYPE_GOAWAY length < 8: " + length);
        }
        final var lastStreamId = reader.readInt();
        final var errorCodeInt = reader.readInt();
        final var opaqueDataLength = length - 8;
        final var errorCode = ErrorCode.fromHttp2(errorCodeInt);
        if (errorCode == null) {
            throw new JayoException("TYPE_GOAWAY unexpected error code: " + errorCodeInt);
        }
        var debugData = ByteString.EMPTY;
        if (opaqueDataLength > 0) { // Must read debug data in order to not corrupt the connection.
            debugData = reader.readByteString(opaqueDataLength);
        }
        handler.goAway(lastStreamId, errorCode, debugData);
    }

    /**
     * Unlike other {@code readXxx()} functions, this one must log the frame before returning.
     */
    private void readWindowUpdate(final @NonNull Handler handler,
                                  final int length,
                                  final int flags,
                                  final int streamId) {
        assert handler != null;

        final long increment;
        try {
            if (length != 4) {
                throw new JayoException("PROTOCOL_ERROR: TYPE_WINDOW_UPDATE length !=4: " + length);
            }
            increment = (reader.readInt() & 0x7fffffffL);
            if (increment == 0L) {
                throw new JayoException("TYPE_WINDOW_UPDATE windowSizeIncrement was 0");
            }
        } catch (Exception e) {
            LOGGER.log(TRACE, frameLog(true, streamId, length, TYPE_WINDOW_UPDATE, flags));
            throw e;
        }
        if (LOGGER.isLoggable(TRACE)) {
            LOGGER.log(TRACE, frameLogWindowUpdate(true, streamId, length, increment));
        }
        handler.windowUpdate(streamId, increment);
    }

    @Override
    public void close() {
        reader.close();
    }

    /**
     * Decompression of the header block occurs above the framing layer. This class lazily reads continuation frames as
     * they are needed by {@link Hpack.Reader#readHeaders()}.
     */
    private static final class ContinuationRawReader implements RawReader {
        private final @NonNull Reader reader;
        private int length = 0;
        private int flags = 0;
        private int streamId = 0;

        private int left = 0;
        private int padding = 0;

        private ContinuationRawReader(final @NonNull Reader reader) {
            assert reader != null;
            this.reader = reader;
        }

        @Override
        public long readAtMostTo(final @NonNull Buffer destination, final long byteCount) {
            assert destination != null;

            while (left == 0) {
                reader.skip(padding);
                padding = 0;
                if ((flags & FLAG_END_HEADERS) != 0) {
                    return -1L;
                }
                readContinuationHeader();
                // TODO: test case for empty continuation header?
            }

            long read = reader.readAtMostTo(destination, Math.min(byteCount, left));
            if (read == -1L) {
                return -1L;
            }
            left -= (int) read;
            return read;
        }

        @Override
        public void close() {
        }

        private void readContinuationHeader() {
            final var previousStreamId = streamId;

            left = readMedium(reader);
            length = left;
            final var type = reader.readByte() & 0xff;
            flags = reader.readByte() & 0xff;
            if (LOGGER.isLoggable(TRACE)) {
                LOGGER.log(TRACE, frameLog(true, streamId, length, type, flags));
            }
            streamId = reader.readInt() & 0x7fffffff;
            if (type != TYPE_CONTINUATION) {
                throw new JayoException(type + " != TYPE_CONTINUATION");
            }
            if (streamId != previousStreamId) {
                throw new JayoException("TYPE_CONTINUATION streamId changed");
            }
        }
    }

    interface Handler {
        void data(final boolean inFinished,
                  final int streamId,
                  final @NonNull Reader reader,
                  final int length);

        /**
         * Create or update incoming headers, creating the corresponding streams if necessary. Frames that trigger this
         * are HEADERS and PUSH_PROMISE.
         *
         * @param inFinished         true if the sender will not send further frames.
         * @param streamId           the stream owning these headers.
         * @param associatedStreamId the stream that triggered the sender to create this stream.
         */
        void headers(final boolean inFinished,
                     final int streamId,
                     final int associatedStreamId,
                     final @NonNull List<@NonNull RealBinaryHeader> headerBlock);

        void rstStream(final int streamId, final @NonNull ErrorCode errorCode);

        void settings(final boolean clearPrevious, final @NonNull Settings settings);

        /**
         * HTTP/2 only.
         */
        void ackSettings();

        /**
         * Read a connection-level ping from the peer. `ack` indicates this is a reply. The data
         * in `payload1` and `payload2` opaque binary, and there are no rules on the content.
         */
        void ping(final boolean ack,
                  final int payload1,
                  final int payload2);

        /**
         * The peer tells us to stop creating streams. It is safe to replay streams with {@code ID > lastGoodStreamId}
         * on a new connection.  In- flight streams with {@code ID <= lastGoodStreamId} can only be replayed on a new
         * connection if they are idempotent.
         *
         * @param lastGoodStreamId the last stream ID the peer processed before sending this message. If
         *                         {@code lastGoodStreamId} is zero, the peer processed no frames.
         * @param errorCode        reason for closing the connection.
         * @param debugData        only valid for HTTP/2; opaque debug data to send.
         */
        void goAway(final int lastGoodStreamId,
                    final @NonNull ErrorCode errorCode,
                    final @NonNull ByteString debugData);

        /**
         * Notifies that an additional {@code windowSizeIncrement} bytes can be sent on {@code streamId}, or the
         * connection if {@code streamId} is zero.
         */
        void windowUpdate(final int streamId, final long windowSizeIncrement);

        /**
         * Called when reading a headers or priority frame. This may be used to change the stream's weight from the
         * default (16) to a new value.
         *
         * @param streamId         stream which has a priority change.
         * @param streamDependency the stream ID this stream is dependent on.
         * @param weight           relative proportion of priority in {@code [1..256]}.
         * @param exclusive        inserts this stream ID as the sole child of {@code streamDependency}.
         */
        void priority(final int streamId,
                      final int streamDependency,
                      final int weight,
                      final boolean exclusive);

        /**
         * HTTP/2 only. Receive a push promise header block.
         * <p>
         * A push promise contains all the headers that pertain to a server-initiated request, and a
         * {@code promisedStreamId} to which response frames will be delivered. Push promise frames are sent
         * as a part of the response to {@code streamId}.
         *
         * @param streamId         client-initiated stream ID.  Must be an odd number.
         * @param promisedStreamId server-initiated stream ID.  Must be an even number.
         * @param requestHeaders   minimally includes {@code :method}, {@code :scheme}, {@code :authority}, and
         *                         {@code :path}.
         */
        void pushPromise(final int streamId,
                         final int promisedStreamId,
                         final @NonNull List<@NonNull RealBinaryHeader> requestHeaders);

        /**
         * HTTP/2 only. Expresses that resources for the connection or a client-initiated stream are available from a
         * different network location or protocol configuration.
         * <p>
         * See <a href="https://tools.ietf.org/html/draft-ietf-httpbis-alt-svc-01">alt-svc</a>
         *
         * @param streamId when a client-initiated stream ID (odd number), the origin of this alternate service is the
         *                 origin of the stream. When zero, the origin is specified in the {@code origin} parameter.
         * @param origin   when present, the <a href="https://tools.ietf.org/html/rfc6454">origin</a> is typically
         *                 represented as a combination of a scheme, host and port. When empty, the origin is that of
         *                 the {@code streamId}.
         * @param protocol an ALPN protocol, such as {@code h2}.
         * @param host     an IP address or hostname.
         * @param port     the IP port associated with the service.
         * @param maxAge   time in seconds that this alternative is considered fresh.
         */
        void alternateService(final int streamId,
                              final @NonNull String origin,
                              final @NonNull ByteString protocol,
                              final @NonNull String host,
                              final int port,
                              final long maxAge);
    }

    private static int readMedium(final @NonNull Reader reader) {
        assert reader != null;

        return ((reader.readByte() & 0xff) << 16)
                | ((reader.readByte() & 0xff) << 8)
                | (reader.readByte() & 0xff);
    }

    private static int lengthWithoutPadding(final int length,
                                            final int flags,
                                            final int padding) {
        var result = length;
        if ((flags & FLAG_PADDED) != 0) {
            result--; // Account for reading the padding length.
        }
        if (padding > result) {
            throw new JayoException("PROTOCOL_ERROR: padding " + padding + " > remaining length " + result);
        }
        result -= padding;
        return result;
    }
}
