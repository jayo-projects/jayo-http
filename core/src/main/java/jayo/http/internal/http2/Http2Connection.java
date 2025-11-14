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
import jayo.http.Headers;
import jayo.http.http2.ErrorCode;
import jayo.http.http2.FlowControlListener;
import jayo.http.http2.JayoConnectionShutdownException;
import jayo.http.http2.PushObserver;
import jayo.http.internal.RealHeaders;
import jayo.http.internal.Utils;
import jayo.scheduler.ScheduledTaskQueue;
import jayo.scheduler.TaskQueue;
import jayo.scheduler.TaskRunner;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.lang.System.Logger.Level.INFO;
import static jayo.http.http2.ErrorCode.REFUSED_STREAM;
import static jayo.http.internal.Utils.JAYO_HTTP_NAME;
import static jayo.http.internal.http2.Settings.DEFAULT_INITIAL_WINDOW_SIZE;

/**
 * A socket connection to a remote peer. A connection hosts streams which can send and receive data.
 * <p>
 * Many methods in this API are <b>synchronous:</b> the call is completed before the method returns. This is typical for
 * Java but atypical for HTTP/2. This is motivated by exception transparency: a {@link JayoException} that was triggered
 * by a certain caller can be caught and handled by that caller.
 */
public final class Http2Connection implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger("jayo.http.http2.Http2Connection");

    // The internal state of this connection is guarded by 'lock'. No blocking operations may be performed while holding
    // this lock!
    //
    // Socket writes are guarded by Http2Writer.
    //
    // Socket reads are unguarded but are only made by the reader thread.
    //
    // Some operations (like SYN_STREAM) need to synchronize on both the Http2Writer (to do blocking I/O) and this
    // (to create streams). Such operations must synchronize on 'this.lock' last.
    // This ensures that we never wait for a blocking operation while holding 'this.lock'.

    public static final int JAYO_HTTP_CLIENT_WINDOW_SIZE = 16 * 1024 * 1024;

    // DEFAULT_SETTINGS
    public static final Settings DEFAULT_SETTINGS = new Settings()
            .set(Settings.INITIAL_WINDOW_SIZE, DEFAULT_INITIAL_WINDOW_SIZE)
            .set(Settings.MAX_FRAME_SIZE, Http2.INITIAL_MAX_FRAME_SIZE);

    private static final int INTERVAL_PING = 1;
    static final int DEGRADED_PING = 2;
    static final int AWAIT_PING = 3;
    static final long DEGRADED_PONG_TIMEOUT_NS = 1_000_000_000; // 1 second.

    static final byte @NonNull [] EMPTY_BYTE_ARRAY = new byte[0];

    /**
     * True if this peer initiated the connection.
     */
    final boolean client;

    /**
     * User code to run in response to incoming streams or settings.
     */
    final @NonNull Listener listener;
    final @NonNull Map<Integer, @NonNull Http2Stream> streams = new HashMap<>();
    final @NonNull String connectionName;
    int lastGoodStreamId = 0;

    /**
     * <a href="https://tools.ietf.org/html/draft-ietf-httpbis-http2-17#section-5.1.1">Stream ID detail</a>
     */
    int nextStreamId;

    private boolean isShutdown = false;

    /**
     * For scheduling everything asynchronous.
     */
    private final @NonNull TaskRunner taskRunner;

    /**
     * Asynchronously writes frames to the outgoing socket.
     */
    private final @NonNull ScheduledTaskQueue writerQueue;

    /**
     * Ensures push promise callbacks events are sent in order per stream.
     */
    private final @NonNull TaskQueue pushQueue;

    /**
     * Notifies the listener of settings changes.
     */
    private final @NonNull TaskQueue settingsListenerQueue;

    /**
     * User code to run in response to push promise events.
     */
    private final @NonNull PushObserver pushObserver;

    // Total number of pings send and received of the corresponding types. All guarded by this.
    private long intervalPingsSent = 0L;
    private long intervalPongsReceived = 0L;
    private long degradedPingsSent = 0L;
    private long degradedPongsReceived = 0L;
    private long awaitPingsSent = 0L;
    private long awaitPongsReceived = 0L;

    /**
     * Consider this connection to be unhealthy if a degraded pong isn't received by this time.
     */
    private long degradedPongDeadlineNs = 0L;

    final @NonNull FlowControlListener flowControlListener;

    /**
     * Settings we communicate to the peer.
     */
    final @NonNull Settings jayoHttpSettings;

    /**
     * Settings we receive from the peer. Changes to the field are guarded by this. The instance is never mutated once
     * it has been assigned.
     */
    @NonNull
    Settings peerSettings;

    /**
     * The bytes consumed and acknowledged by the application.
     */
    @NonNull
    RealWindowCounter readBytes;

    /**
     * The total number of bytes produced by the application.
     */
    long writeBytesTotal = 0L;

    /**
     * The total number of bytes permitted to be produced according to {@code WINDOW_UPDATE} frames.
     */
    long writeBytesMaximum;

    final @NonNull Socket socket;
    final @NonNull Http2Writer writer;

    final @NonNull ReaderRunnable readerRunnable;

    // Guarded by this.
    private final @NonNull Set<Integer> currentPushRequests = new LinkedHashSet<>();

    final @NonNull Lock lock = new ReentrantLock();
    private final @NonNull Condition condition = lock.newCondition();

    public Http2Connection(final @NonNull Builder builder) {
        assert builder != null;

        this.client = builder.client;
        this.listener = builder.listener;
        this.taskRunner = builder.taskRunner;
        this.writerQueue = taskRunner.newScheduledQueue();
        this.pushQueue = taskRunner.newQueue();
        this.settingsListenerQueue = taskRunner.newQueue();
        this.pushObserver = builder.pushObserver;
        this.flowControlListener = builder.flowControlListener;

        this.jayoHttpSettings = new Settings();
        // Flow control was designed more for servers or proxies than edge clients. If we are a client, set the flow
        // control window to 16MiB.  This avoids thrashing window updates every 64KiB, yet small enough to avoid blowing
        // up the heap.
        if (builder.client) {
            jayoHttpSettings.set(Settings.INITIAL_WINDOW_SIZE, JAYO_HTTP_CLIENT_WINDOW_SIZE);
        }
        peerSettings = DEFAULT_SETTINGS;

        this.nextStreamId = (builder.client) ? 3 : 2;
        readBytes = new RealWindowCounter(0);
        writeBytesMaximum = peerSettings.initialWindowSize();

        this.socket = builder.socket;
        assert builder.connectionName != null;
        this.connectionName = builder.connectionName;
        writer = new Http2Writer(socket.getWriter(), client);
        readerRunnable = new ReaderRunnable(new Http2Reader(socket.getReader(), client));

        if (!builder.pingInterval.equals(Duration.ZERO)) {
            initializePingInterval(builder.pingInterval);
        }
    }

    private void initializePingInterval(final @NonNull Duration pingInterval) {
        assert pingInterval != null;

        final var pingIntervalNanos = pingInterval.toNanos();
        writerQueue.schedule(connectionName + " ping", pingIntervalNanos, () -> {
            boolean failDueToMissingPong;
            lock.lock();
            try {
                if (intervalPongsReceived < intervalPingsSent) {
                    failDueToMissingPong = true;
                } else {
                    intervalPingsSent++;
                    failDueToMissingPong = false;
                }
            } finally {
                lock.unlock();
            }
            if (failDueToMissingPong) {
                failConnection(null);
                return -1L;
            } else {
                writePing(false, INTERVAL_PING, 0);
                return pingIntervalNanos;
            }
        });
    }

    /**
     * Returns the number of {@linkplain Http2Stream#isOpen() open streams} on this connection.
     */
    int openStreamCount() {
        lock.lock();
        try {
            return streams.size();
        } finally {
            lock.unlock();
        }
    }

    private @Nullable Http2Stream getStream(final int id) {
        lock.lock();
        try {
            return streams.get(id);
        } finally {
            lock.unlock();
        }
    }

    @Nullable
    Http2Stream removeStream(final int streamId) {
        lock.lock();
        try {
            final var stream = streams.remove(streamId);

            // The removed stream may be blocked on a connection-wide window update.
            condition.signalAll();

            return stream;
        } finally {
            lock.unlock();
        }
    }

    void updateConnectionFlowControl(final long read) {
        lock.lock();
        try {
            readBytes.update(read, 0L);
            final var readBytesToAcknowledge = readBytes.getUnacknowledged();
            if (readBytesToAcknowledge >= jayoHttpSettings.initialWindowSize() / 2) {
                writeWindowUpdateLater(0, readBytesToAcknowledge);
                readBytes.update(0L, readBytesToAcknowledge);
            }
            flowControlListener.receivingConnectionWindowChanged(readBytes);
        } finally {
            lock.unlock();
        }
    }

    /**
     * @param associatedStreamId the stream that triggered the sender to create this stream.
     * @param out                true to create an output stream that we can use to send data to the remote peer.
     *                           Corresponds to {@code FLAG_FIN}.
     * @return a new server-initiated stream.
     * @throws JayoException an IO Exception.
     */
    @NonNull
    Http2Stream pushStream(final int associatedStreamId,
                           final @NonNull List<@NonNull RealBinaryHeader> requestHeaders,
                           final boolean out) {
        assert requestHeaders != null;

        if (client) {
            throw new IllegalStateException("Client cannot push requests.");
        }
        return newStream(associatedStreamId, requestHeaders, out);
    }

    /**
     * @param out true to create an output stream that we can use to send data to the remote peer.
     *            Corresponds to {@code FLAG_FIN}.
     * @return a new locally initiated stream.
     * @throws JayoException an IO Exception.
     */
    @NonNull
    Http2Stream newStream(final @NonNull List<@NonNull RealBinaryHeader> requestHeaders, final boolean out) {
        assert requestHeaders != null;
        return newStream(0, requestHeaders, out);
    }

    private @NonNull Http2Stream newStream(final int associatedStreamId,
                                           final @NonNull List<@NonNull RealBinaryHeader> requestHeaders,
                                           final boolean out) {
        assert requestHeaders != null;

        final var outFinished = !out;
        final var inFinished = false;
        final boolean flushHeaders;
        final Http2Stream stream;
        final int streamId;

        writer.lock.lock();
        try {
            lock.lock();
            try {
                if (nextStreamId > Integer.MAX_VALUE / 2) {
                    shutdown(REFUSED_STREAM);
                }
                if (isShutdown) {
                    throw new JayoConnectionShutdownException();
                }
                streamId = nextStreamId;
                nextStreamId += 2;
                stream = new Http2Stream(streamId, this, outFinished, inFinished, null);
                flushHeaders = !out ||
                        writeBytesTotal >= writeBytesMaximum ||
                        stream.writeBytesTotal >= stream.writeBytesMaximum;
                if (stream.isOpen()) {
                    streams.put(streamId, stream);
                }
            } finally {
                lock.unlock();
            }
            if (associatedStreamId == 0) {
                writer.headers(outFinished, streamId, requestHeaders);
            } else {
                if (client) {
                    throw new IllegalArgumentException("client streams shouldn't have associated stream IDs");
                }
                // HTTP/2 has a PUSH_PROMISE frame.
                writer.pushPromise(associatedStreamId, streamId, requestHeaders);
            }
        } finally {
            writer.lock.unlock();
        }

        if (flushHeaders) {
            writer.flush();
        }

        return stream;
    }

    void writeHeaders(final int streamId,
                      final boolean outFinished,
                      final @NonNull List<@NonNull RealBinaryHeader> alternating) {
        assert alternating != null;
        writer.headers(outFinished, streamId, alternating);
    }

    /**
     * Callers of this method are not thread-safe and sometimes on application threads. Most often, this method will be
     * called to send buffer worth of data to the peer.
     * <p>
     * Writes are subject to the write window of the stream and the connection. Until there is a window sufficient to
     * send {@code byteCount}, the caller will block. For example, a user of {@code HttpURLConnection} who flushes more
     * bytes to the output stream than the connection's write window will block.
     * <p>
     * Zero {@code byteCount} writes are not subject to flow control and will not block. The only use case for zero
     * {@code byteCount} is closing a flushed output stream.
     *
     * @throws JayoException an IO Exception.
     */
    void writeData(final int streamId,
                   final boolean outFinished,
                   final @Nullable Buffer buffer,
                   final long byteCount) {
        // Empty data frames are not flow-controlled.
        if (byteCount == 0L) {
            writer.data(outFinished, streamId, buffer, 0);
            return;
        }

        var remaining = byteCount;
        while (remaining > 0L) {
            int toWrite;
            lock.lock();
            try {
                try {
                    while (writeBytesTotal >= writeBytesMaximum) {
                        // Before blocking, confirm that the stream we're writing is still open. It's possible that the
                        // stream has since been closed (such as if this write timed out.)
                        if (!streams.containsKey(streamId)) {
                            throw new JayoException("stream closed");
                        }
                        condition.await(); // Wait until we receive a WINDOW_UPDATE.
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // Retain interrupted status.
                    throw new JayoInterruptedIOException("InterruptedException caught while waiting for WINDOW_UPDATE");
                }

                toWrite = (int) Math.min(remaining, writeBytesMaximum - writeBytesTotal);
                toWrite = Math.min(toWrite, writer.maxDataLength());
                writeBytesTotal += toWrite;
            } finally {
                lock.unlock();
            }

            remaining -= toWrite;
            writer.data(outFinished && remaining == 0L, streamId, buffer, toWrite);
        }
    }

    void writeSynResetLater(final int streamId, final @NonNull ErrorCode errorCode) {
        assert errorCode != null;

        writerQueue.execute(connectionName + "[" + streamId + "] writeSynReset", true, () -> {
            try {
                writeSynReset(streamId, errorCode);
            } catch (JayoException je) {
                failConnection(je);
            }
        });
    }

    void writeSynReset(final int streamId, final @NonNull ErrorCode statusCode) {
        assert statusCode != null;
        writer.rstStream(streamId, statusCode);
    }

    void writeWindowUpdateLater(final int streamId, final long unacknowledgedBytesRead) {
        writerQueue.execute(connectionName + "[" + streamId + "] windowUpdate", true, () -> {
            try {
                writer.windowUpdate(streamId, unacknowledgedBytesRead);
            } catch (JayoException je) {
                failConnection(je);
            }
        });
    }

    void writePing(final boolean reply,
                   final int payload1,
                   final int payload2) {
        try {
            writer.ping(reply, payload1, payload2);
        } catch (JayoException je) {
            failConnection(je);
        }
    }

    /**
     * For testing: sends a ping and waits for a pong.
     */
    void writePingAndAwaitPong() throws InterruptedException {
        writePing();
        awaitPong();
    }

    /**
     * For testing: sends a ping to be awaited with {@link #awaitPong()}.
     */
    void writePing() {
        lock.lock();
        try {
            awaitPingsSent++;
        } finally {
            lock.unlock();
        }

        // 0x4f 0x4b 0x6f 0x6b is "OKok".
        writePing(false, AWAIT_PING, 0x4f4b6f6b);
    }

    /**
     * For testing: awaits a pong.
     */
    void awaitPong() throws InterruptedException {
        lock.lock();
        try {
            while (awaitPongsReceived < awaitPingsSent) {
                condition.await();
            }
        } finally {
            lock.unlock();
        }
    }

    void flush() {
        writer.flush();
    }

    /**
     * Degrades this connection such that new streams can neither be created locally nor accepted from the remote peer.
     * Existing streams are not impacted. This is intended to permit an endpoint to gracefully stop accepting new
     * requests without harming previously established streams.
     */
    public void shutdown(final @NonNull ErrorCode statusCode) {
        assert statusCode != null;

        writer.lock.lock();
        try {
            final int lastGoodStreamId;
            lock.lock();
            try {
                if (isShutdown) {
                    return;
                }
                isShutdown = true;
                lastGoodStreamId = this.lastGoodStreamId;
            } finally {
                lock.unlock();
            }
            // TODO: propagate exception message into debugData.
            // TODO: configure a timeout on the reader so that it doesn’t block forever.
            writer.goAway(lastGoodStreamId, statusCode, EMPTY_BYTE_ARRAY);
        } finally {
            writer.lock.unlock();
        }
    }

    /**
     * Closes this connection. This cancels all open streams and unanswered pings. It closes the underlying input and
     * output streams and shuts down internal task queues.
     */
    @Override
    public void close() {
        close(ErrorCode.NO_ERROR, ErrorCode.CANCEL, null);
    }

    void close(final @NonNull ErrorCode connectionCode,
               final @NonNull ErrorCode streamCode,
               final @Nullable JayoException cause) {
        assert connectionCode != null;
        assert streamCode != null;

        try {
            shutdown(connectionCode);
        } catch (JayoException ignored) {
        }

        Http2Stream[] streamsToClose = null;
        lock.lock();
        try {
            if (!streams.isEmpty()) {
                streamsToClose = streams.values().toArray(new Http2Stream[0]);
                streams.clear();
            }
        } finally {
            lock.unlock();
        }

        try {
            if (streamsToClose != null) {
                for (final var stream : streamsToClose) {
                    stream.close(streamCode, cause);
                }
            }

            // Close the writer to release its resources (such as deflaters).
            writer.close();


            // Cancel the socket to break out the reader thread, which will clean up after itself.
            socket.cancel();
        } catch (JayoException ignored) {
        }

        // Release the threads.
        writerQueue.shutdown();
        pushQueue.shutdown();
        settingsListenerQueue.shutdown();
    }

    private void failConnection(final @Nullable JayoException je) {
        close(ErrorCode.PROTOCOL_ERROR, ErrorCode.PROTOCOL_ERROR, je);
    }

    /**
     * Sends any initial frames and starts reading frames from the remote peer. This should be called after
     * {@link Builder#build()} for all new connections.
     *
     * @param sendConnectionPreface true to send connection preface frames. This should always be true except for in
     *                              tests that don't check for a connection preface.
     */
    public void start(final boolean sendConnectionPreface) {
        if (sendConnectionPreface) {
            writer.connectionPreface();
            writer.settings(jayoHttpSettings);
            final var windowSize = jayoHttpSettings.initialWindowSize();
            if (windowSize != DEFAULT_INITIAL_WINDOW_SIZE) {
                writer.windowUpdate(0, windowSize - DEFAULT_INITIAL_WINDOW_SIZE);
            }
        }
        // Thread doesn't use client Dispatcher, since it is scoped potentially across clients via ConnectionPool.
        taskRunner.execute(connectionName, true, readerRunnable);
    }

    /**
     * Merges {@code settings} into this peer's settings and sends them to the remote peer.
     */
    void setSettings(final @NonNull Settings settings) {
        writer.lock.lock();
        try {
            lock.lock();
            try {
                if (isShutdown) {
                    throw new JayoConnectionShutdownException();
                }
                jayoHttpSettings.merge(settings);
            } finally {
                lock.unlock();
            }
            writer.settings(settings);
        } finally {
            writer.lock.unlock();
        }
    }

    public boolean isHealthy(final long nowNs) {
        lock.lock();
        try {
            if (isShutdown) {
                return false;
            }
            // A degraded pong is overdue.
            if (degradedPongsReceived < degradedPingsSent && nowNs >= degradedPongDeadlineNs) {
                return false;
            }

            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * HTTP/2 can have both stream timeouts (due to a problem with a single stream) and connection timeouts (due to a
     * problem with the transport). When a stream times out, we don't know whether the problem impacts just one stream
     * or the entire connection.
     * <p>
     * To differentiate the two cases, we ping the server when a stream times out. If the overall connection is fine,
     * the ping will receive a pong; otherwise it won't.
     * <p>
     * The deadline to respond to this ping attempts to limit the cost of being wrong. If it is too long, streams
     * created while we await the pong will reuse broken connections and inevitably fail. If it is too short, slow
     * connections will be marked as failed, and extra TCP and TLS handshakes will be required.
     * <p>
     * The deadline is currently hardcoded. We may make this configurable in the future!
     */
    void sendDegradedPingLater() {
        lock.lock();
        try {
            if (degradedPongsReceived < degradedPingsSent) {
                return; // Already awaiting a degraded pong.
            }
            degradedPingsSent++;
            degradedPongDeadlineNs = System.nanoTime() + DEGRADED_PONG_TIMEOUT_NS;
        } finally {
            lock.unlock();
        }
        writerQueue.execute(connectionName + " ping", true,
                () -> writePing(false, DEGRADED_PING, 0));
    }

    public static final class Builder {
        /**
         * True if this peer initiated the connection; false if this peer accepted the connection.
         */
        private final boolean client;
        private final @NonNull TaskRunner taskRunner;
        private /* lateinit */ Socket socket;
        private /* lateinit */ String connectionName;
        private @NonNull Listener listener = Listener.REFUSE_INCOMING_STREAMS;
        private @NonNull PushObserver pushObserver = PushObserver.CANCEL;
        private @NonNull Duration pingInterval = Duration.ZERO;
        private @NonNull FlowControlListener flowControlListener = FlowControlListener.NONE;

        public Builder(final boolean client, final @NonNull TaskRunner taskRunner) {
            assert taskRunner != null;

            this.client = client;
            this.taskRunner = taskRunner;
        }

        public @NonNull Builder socket(final @NonNull Socket socket, final @NonNull String peerName) {
            assert socket != null;
            assert peerName != null;
            this.socket = socket;
            this.connectionName = JAYO_HTTP_NAME + (client ? " Client " : " Server ") + peerName;
            return this;
        }

        public @NonNull Builder listener(final @NonNull Listener listener) {
            assert listener != null;
            this.listener = listener;
            return this;
        }

        public @NonNull Builder pushObserver(final @NonNull PushObserver pushObserver) {
            assert pushObserver != null;
            this.pushObserver = pushObserver;
            return this;
        }

        public @NonNull Builder pingInterval(final @NonNull Duration pingInterval) {
            assert pingInterval != null;
            this.pingInterval = pingInterval;
            return this;
        }

        public @NonNull Builder flowControlListener(final @NonNull FlowControlListener flowControlListener) {
            assert flowControlListener != null;
            this.flowControlListener = flowControlListener;
            return this;
        }

        public @NonNull Http2Connection build() {
            return new Http2Connection(this);
        }
    }

    /**
     * Methods in this class must not lock Http2Writer. If a method needs to write a frame, create an async task to do
     * so.
     */
    final class ReaderRunnable implements Http2Reader.Handler, Runnable {
        private final @NonNull Http2Reader reader;

        private ReaderRunnable(final @NonNull Http2Reader reader) {
            assert reader != null;
            this.reader = reader;
        }

        @Override
        public void run() {
            var connectionErrorCode = ErrorCode.INTERNAL_ERROR;
            var streamErrorCode = ErrorCode.INTERNAL_ERROR;
            JayoException errorException = null;
            try {
                reader.readConnectionPreface(this);
                while (reader.nextFrame(false, this)) {
                }
                connectionErrorCode = ErrorCode.NO_ERROR;
                streamErrorCode = ErrorCode.CANCEL;
            } catch (JayoException e) {
                errorException = e;
                connectionErrorCode = ErrorCode.PROTOCOL_ERROR;
                streamErrorCode = ErrorCode.PROTOCOL_ERROR;
            } finally {
                close(connectionErrorCode, streamErrorCode, errorException);
                Utils.closeQuietly(reader);
            }
        }

        @Override
        public void data(final boolean inFinished,
                         final int streamId,
                         final @NonNull Reader reader,
                         final int length) {
            assert reader != null;

            if (pushedStream(streamId)) {
                pushDataLater(streamId, reader, length, inFinished);
                return;
            }
            final var dataStream = getStream(streamId);
            if (dataStream == null) {
                writeSynResetLater(streamId, ErrorCode.PROTOCOL_ERROR);
                updateConnectionFlowControl(length);
                reader.skip(length);
                return;
            }
            dataStream.receiveData(reader, length);
            if (inFinished) {
                dataStream.receiveHeaders(Headers.EMPTY, true);
            }
        }

        @Override
        public void headers(final boolean inFinished,
                            final int streamId,
                            final int associatedStreamId,
                            final @NonNull List<@NonNull RealBinaryHeader> headerBlock) {
            assert headerBlock != null;

            if (pushedStream(streamId)) {
                pushHeadersLater(streamId, headerBlock, inFinished);
                return;
            }

            Http2Stream stream;
            lock.lock();
            try {
                stream = getStream(streamId);

                if (stream == null) {
                    if (isShutdown || // If we're shutdown, don't bother with this stream.
                            // If the stream ID is less than the last created ID, assume it's already closed.
                            streamId <= lastGoodStreamId ||
                            // If the stream ID is in the client's namespace, assume it's already closed.
                            streamId % 2 == nextStreamId % 2) {
                        return;
                    }

                    // Create a stream.
                    final var headers = toHeaders(headerBlock);
                    final var newStream = new Http2Stream(streamId, Http2Connection.this,
                            false, inFinished, headers);
                    lastGoodStreamId = streamId;
                    streams.put(streamId, newStream);

                    // We execute this async task individually instead of using a task queue because all streams should
                    // be handled in parallel.
                    taskRunner.execute(connectionName + "[" + streamId + "] onStream", true, () -> {
                        try {
                            listener.onStream(newStream);
                        } catch (JayoException e) {
                            if (LOGGER.isLoggable(INFO)) {
                                LOGGER.log(INFO, "Http2Connection.Listener failure for " + connectionName, e);
                            }
                            try {
                                newStream.close(ErrorCode.PROTOCOL_ERROR, e);
                            } catch (JayoException ignored) {
                            }
                        }
                    });
                    return;
                }
            } finally {
                lock.unlock();
            }

            assert stream != null;
            // Update an existing stream.
            stream.receiveHeaders(toHeaders(headerBlock), inFinished);
        }

        private static @NonNull Headers toHeaders(final @NonNull List<@NonNull RealBinaryHeader> binaryHeaders) {
            final var builder = new RealHeaders.Builder();
            for (final var binaryHeader : binaryHeaders) {
                builder.addLenient(binaryHeader.getName().decodeToString(), binaryHeader.getValue().decodeToString());
            }
            return builder.build();
        }

        @Override
        public void rstStream(final int streamId, final @NonNull ErrorCode errorCode) {
            assert errorCode != null;

            if (pushedStream(streamId)) {
                pushResetLater(streamId, errorCode);
                return;
            }
            final var rstStream = removeStream(streamId);
            if (rstStream != null) {
                rstStream.receiveRstStream(errorCode);
            }
        }

        @Override
        public void settings(final boolean clearPrevious, final @NonNull Settings settings) {
            assert settings != null;
            writerQueue.execute(connectionName + " applyAndAckSettings", true,
                    () -> applyAndAckSettings(clearPrevious, settings));
        }

        /**
         * Apply inbound settings and send an acknowledgement to the peer that provided them.
         * <p>
         * We need to apply the settings and ack them atomically. This is because some HTTP/2 implementations (nghttp2)
         * forbid peers from taking advantage of settings before they have acknowledged! In particular, we shouldn't
         * send frames that assume a new {@code initialWindowSize} until we send the frame that acknowledges this new
         * size.
         * <p>
         * Since we can't ACK settings on the current reader thread (the reader thread can't write), we execute all peer
         * settings logic on the writer thread. This relies on the fact that the writer task queue won't reorder tasks;
         * otherwise settings could be applied in the opposite order than received.
         */
        void applyAndAckSettings(final boolean clearPrevious, final @NonNull Settings settings) {
            assert settings != null;

            long delta;
            Http2Stream[] streamsToNotify;
            Settings newPeerSettings;
            writer.lock.lock();
            try {
                final var previousPeerSettings = peerSettings;
                newPeerSettings = clearPrevious
                        ? settings
                        : new Settings().merge(previousPeerSettings).merge(settings);

                final var peerInitialWindowSize = newPeerSettings.initialWindowSize();
                delta = peerInitialWindowSize - previousPeerSettings.initialWindowSize();
                streamsToNotify = (delta == 0L || streams.isEmpty())
                        ? null // No adjustment is necessary.
                        : streams.values().toArray(new Http2Stream[0]);
                peerSettings = newPeerSettings;

                settingsListenerQueue.execute(connectionName + " onSettings", true,
                        () -> listener.onSettings(Http2Connection.this, newPeerSettings));
            } finally {
                writer.lock.unlock();
            }
            try {
                writer.applyAndAckSettings(newPeerSettings);
            } catch (JayoException je) {
                failConnection(je);
            }
            if (streamsToNotify != null) {
                for (final var stream : streamsToNotify) {
                    stream.lock.lock();
                    try {
                        stream.addBytesToWriteWindow(delta);
                    } finally {
                        stream.lock.unlock();
                    }
                }
            }
        }

        @Override
        public void ackSettings() {
            // TODO: If we don't get this callback after sending settings to the peer, SETTINGS_TIMEOUT.
        }

        @Override
        public void ping(final boolean ack,
                         final int payload1,
                         final int payload2) {
            if (ack) {
                lock.lock();
                try {
                    switch (payload1) {
                        case INTERVAL_PING -> intervalPongsReceived++;
                        case DEGRADED_PING -> degradedPongsReceived++;
                        case AWAIT_PING -> {
                            awaitPongsReceived++;
                            condition.signalAll();
                        }
                        default -> {
                            // Ignore an unexpected pong.
                        }
                    }
                } finally {
                    lock.unlock();
                }
            } else {
                // Send a reply to a client ping if this is a server and vice versa.
                writerQueue.execute(connectionName + " ping", true,
                        () -> writePing(true, payload1, payload2));
            }
        }

        @Override
        public void goAway(final int lastGoodStreamId,
                           final @NonNull ErrorCode errorCode,
                           final @NonNull ByteString debugData) {
            assert errorCode != null;
            assert debugData != null;

//            if (debugData.byteSize() > 0) { // TODO: log the debugData
//            }

            // Copy the streams first. We don't want to hold a lock when we call receiveRstStream().
            Http2Stream[] streamsCopy;
            lock.lock();
            try {
                streamsCopy = streams.values().toArray(new Http2Stream[0]);
                isShutdown = true;
            } finally {
                lock.unlock();
            }

            for (final var http2Stream : streamsCopy) {
                if (http2Stream.id > lastGoodStreamId && http2Stream.isLocallyInitiated()) {
                    http2Stream.receiveRstStream(ErrorCode.REFUSED_STREAM);
                    removeStream(http2Stream.id);
                }
            }
        }

        @Override
        public void windowUpdate(final int streamId, final long windowSizeIncrement) {
            if (streamId == 0) {
                lock.lock();
                try {
                    writeBytesMaximum += windowSizeIncrement;
                    condition.signalAll();
                } finally {
                    lock.unlock();
                }
            } else {
                final var stream = getStream(streamId);
                if (stream != null) {
                    stream.lock.lock();
                    try {
                        stream.addBytesToWriteWindow(windowSizeIncrement);
                    } finally {
                        stream.lock.unlock();
                    }
                }
            }
        }

        @Override
        public void priority(int streamId, int streamDependency, int weight, boolean exclusive) {
            // TODO: honor priority.
        }

        @Override
        public void pushPromise(final int streamId,
                                final int promisedStreamId,
                                final @NonNull List<@NonNull RealBinaryHeader> requestHeaders) {
            assert requestHeaders != null;
            pushRequestLater(promisedStreamId, requestHeaders);
        }

        @Override
        public void alternateService(final int streamId,
                                     final @NonNull String origin,
                                     final @NonNull ByteString protocol,
                                     final @NonNull String host,
                                     final int port,
                                     final long maxAge) {
            assert origin != null;
            assert protocol != null;
            assert host != null;
            // TODO: register alternate service.
        }
    }

    /**
     * Even, positive-numbered streams are pushed streams in HTTP/2.
     */
    private boolean pushedStream(final int streamId) {
        return streamId != 0 && (streamId & 1) == 0;
    }

    private void pushRequestLater(final int streamId, final @NonNull List<@NonNull RealBinaryHeader> requestHeaders) {
        assert requestHeaders != null;

        lock.lock();
        try {
            if (currentPushRequests.contains(streamId)) {
                writeSynResetLater(streamId, ErrorCode.PROTOCOL_ERROR);
                return;
            }
            currentPushRequests.add(streamId);
        } finally {
            lock.unlock();
        }
        pushQueue.execute(connectionName + "[" + streamId + "] onRequest", true, () -> {
            final var cancel = pushObserver.onRequest(streamId, requestHeaders);
            if (cancel) {
                try {
                    writer.rstStream(streamId, ErrorCode.CANCEL);
                    lock.lock();
                    try {
                        currentPushRequests.remove(streamId);
                    } finally {
                        lock.unlock();
                    }
                } catch (JayoException ignored) {
                }
            }
        });
    }

    private void pushHeadersLater(final int streamId,
                                  final @NonNull List<@NonNull RealBinaryHeader> requestHeaders,
                                  final boolean inFinished) {
        assert requestHeaders != null;
        pushQueue.execute(connectionName + "[" + streamId + "] onHeaders", true, () -> {
            final var cancel = pushObserver.onHeaders(streamId, requestHeaders, inFinished);
            try {
                if (cancel) {
                    writer.rstStream(streamId, ErrorCode.CANCEL);
                }
                if (cancel || inFinished) {
                    lock.lock();
                    try {
                        currentPushRequests.remove(streamId);
                    } finally {
                        lock.unlock();
                    }
                }
            } catch (JayoException ignored) {
            }
        });
    }

    /**
     * Eagerly reads {@code byteCount} bytes from the source before launching a background task to process the data.
     * This avoids corrupting the stream.
     */
    private void pushDataLater(final int streamId,
                               final @NonNull Reader reader,
                               final int byteCount,
                               final boolean inFinished
    ) {
        assert reader != null;

        final var buffer = Buffer.create();
        reader.require(byteCount); // Eagerly read the frame before firing the client thread.
        reader.readAtMostTo(buffer, byteCount);
        pushQueue.execute(connectionName + "[" + streamId + "] onData", true, () -> {
            try {
                boolean cancel = pushObserver.onData(streamId, buffer, byteCount, inFinished);
                if (cancel) {
                    writer.rstStream(streamId, ErrorCode.CANCEL);
                }
                if (cancel || inFinished) {
                    lock.lock();
                    try {
                        currentPushRequests.remove(streamId);
                    } finally {
                        lock.unlock();
                    }
                }
            } catch (JayoException ignored) {
            }
        });
    }

    private void pushResetLater(final int streamId, final @NonNull ErrorCode errorCode) {
        assert errorCode != null;
        pushQueue.execute(connectionName + "[" + streamId + "] onReset", true, () -> {
            pushObserver.onReset(streamId, errorCode);
            lock.lock();
            try {
                currentPushRequests.remove(streamId);
            } finally {
                lock.unlock();
            }
        });
    }

    /**
     * Listener of streams and settings initiated by the peer.
     */
    public static abstract class Listener {
        /**
         * Handle a new stream from this connection's peer. Implementations should respond by either
         * {@linkplain Http2Stream#writeHeaders replying to the stream} or {@linkplain Http2Stream#close closing it}.
         * This response does not need to be synchronous.
         * <p>
         * Multiple calls to this method may be made concurrently.
         *
         * @throws JayoException an IO Exception.
         */
        protected abstract void onStream(final @NonNull Http2Stream stream);

        /**
         * Notification that the connection's peer's settings may have changed to {@code settings}.
         * Implementations should take appropriate action to handle the updated settings.
         * <p>
         * Methods to this method may be made concurrently with {@link #onStream(Http2Stream)}. But calls to this method
         * are serialized.
         */
        protected void onSettings(final @NonNull Http2Connection connection, final @NonNull Settings settings) {
        }

        static @NonNull Listener REFUSE_INCOMING_STREAMS = new Listener() {
            @Override
            protected void onStream(final @NonNull Http2Stream stream) {
                assert stream != null;
                stream.close(REFUSED_STREAM, null);
            }
        };
    }
}
