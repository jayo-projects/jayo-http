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
import jayo.http.Headers;
import jayo.http.http2.ErrorCode;
import jayo.http.http2.JayoStreamResetException;
import jayo.tools.AsyncTimeout;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A logical bidirectional HTTP/2 stream.
 */
public final class Http2Stream implements RawSocket {
    private static final long EMIT_BUFFER_SIZE = 16384L;

    // Internal state is guarded by `lock`. No long-running or potentially blocking operations are performed while the
    // lock is held.
    final @NonNull Lock lock = new ReentrantLock();
    private final @NonNull Condition condition = lock.newCondition();

    final int id;
    final @NonNull Http2Connection connection;

    /**
     * The bytes consumed and acknowledged by the stream.
     */
    final @NonNull RealWindowCounter readBytes;

    /**
     * The total number of bytes produced by the application.
     */
    long writeBytesTotal = 0L;

    /**
     * The total number of bytes permitted to be produced by the incoming ` WINDOW_UPDATE ` frame.
     */
    long writeBytesMaximum;

    /**
     * Received headers yet to be {@linkplain #takeHeaders(boolean) taken}.
     */
    private final @NonNull Deque<@NonNull Headers> headersQueue = new ArrayDeque<>();

    /**
     * True if response headers have been sent or received.
     */
    private boolean hasResponseHeaders = false;

    final @NonNull FramingRawReader reader;
    final @NonNull FramingRawWriter writer;

    /**
     * This timeout watchdog will call {@link #timedOut()} if the timeout is reached. In that case we close the stream
     * (asynchronously) which will notify the waiting thread.
     */
    private final @NonNull AsyncTimeout timeout = AsyncTimeout.create(this::timedOut);
    private static final @NonNull String TIMEOUT_MSG = "HTTP2 stream timeout";
    long readTimeoutNanos = 0L;
    long writeTimeoutNanos = 0L;

    /**
     * The reason why this stream was closed, or null if it closed normally or has not yet been closed. This field must
     * be accessed from a locked code block.
     * <p>
     * If there are multiple reasons to abnormally close this stream (such as both peers closing it
     * near-simultaneously), then this is the first reason known to this peer.
     */
    private @Nullable ErrorCode errorCode = null;

    /**
     * The exception that explains {@link #errorCode}. Null if no exception was provided.
     */
    private @Nullable JayoException errorException = null;

    Http2Stream(final int id,
                final @NonNull Http2Connection connection,
                final boolean outFinished,
                final boolean inFinished,
                final @Nullable Headers headers) {
        assert connection != null;

        this.id = id;
        this.connection = connection;

        if (headers != null) {
            // locally initiated streams shouldn't have headers yet
            if (isLocallyInitiated()) {
                throw new IllegalStateException("locally initiated streams shouldn't have headers yet");
            }
            headersQueue.add(headers);
        } else {
            // remotely initiated streams should have headers
            if (!isLocallyInitiated()) {
                throw new IllegalStateException("remotely initiated streams should have headers");
            }
        }

        readBytes = new RealWindowCounter(id);
        writeBytesMaximum = connection.peerSettings.initialWindowSize();
        reader = new FramingRawReader(connection.jayoHttpSettings.initialWindowSize(), inFinished);
        writer = new FramingRawWriter(outFinished);
    }

    /**
     * @return true if this stream was created by this peer.
     */
    boolean isLocallyInitiated() {
        final var streamIsClient = (id & 1) == 1;
        return connection.client == streamIsClient;
    }

    /**
     * @return true if this stream is open. A stream is open until either:
     * <ul>
     * <li>A {@code SYN_RESET} frame abnormally terminates the stream.
     * <li>Both input and output streams have transmitted all data and headers.
     * </ul>
     * Note that the input stream may continue to yield data even after a stream reports itself as not open. This is
     * because input data is buffered.
     */
    boolean isOpen() {
        // no need to lock because this method is called from a lock-protected block or just after instantiation.
        if (errorCode != null) {
            return false;
        }
        if ((reader.finished || reader.closed) &&
                (writer.finished || writer.closed) &&
                hasResponseHeaders) {
            return false;
        }
        return true;
    }

    boolean isSourceComplete() {
        lock.lock();
        try {
            return reader.finished && reader.readBuffer.exhausted();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes and returns the stream's received response headers, blocking if necessary until headers have been
     * received. If the returned list contains multiple blocks of headers, the blocks will be delimited by 'null'.
     *
     * @param callerIsIdle true if the caller isn't sending any more bytes until the peer responds. This is true after a
     *                     {@code Expect-Continue} request, false for duplex requests, and false for all other requests.
     */
    @NonNull
    Headers takeHeaders(final boolean callerIsIdle) {
        lock.lock();
        try {
            while (headersQueue.isEmpty() && errorCode == null) {
                final var doReadTimeout = callerIsIdle || doReadTimeout();
                AsyncTimeout.Node node = null;
                if (doReadTimeout) {
                    node = timeout.enter(readTimeoutNanos);
                }
                try {
                    waitForIo();
                } finally {
                    if (doReadTimeout) {
                        exitAndThrowIfTimedOut(node);
                    }
                }
            }
            if (!headersQueue.isEmpty()) {
                return headersQueue.removeFirst();
            }
            if (errorException != null) {
                throw errorException;
            }
            // else
            throw new JayoStreamResetException(errorCode);
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return the trailers if they're immediately available.
     */
    @Nullable
    Headers peekTrailers() {
        lock.lock();
        try {
            if (reader.finished && reader.receiveBuffer.exhausted() && reader.readBuffer.exhausted()) {
                return (reader.trailers != null) ? reader.trailers : Headers.EMPTY;
            }
            if (errorCode != null) {
                throw errorException != null ? errorException : new JayoStreamResetException(errorCode);
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Sends a reply to an incoming stream.
     *
     * @param outFinished  true to eagerly finish the output stream to send data to the remote peer.
     *                     Corresponds to {@code FLAG_FIN}.
     * @param flushHeaders true to force flush the response headers. This should be true unless the response body exists
     *                     and will be written immediately.
     */
    void writeHeaders(final @NonNull List<@NonNull RealBinaryHeader> responseHeaders,
                      final boolean outFinished,
                      final boolean flushHeaders) {
        var localFlushHeaders = flushHeaders;
        lock.lock();
        try {
            this.hasResponseHeaders = true;
            if (outFinished) {
                this.writer.finished = true;
                condition.signalAll(); // Because doReadTimeout() may have changed.
            }
        } finally {
            lock.unlock();
        }

        // Only DATA frames are subject to flow-control. Transmit the HEADER frame if the connection flow-control window
        // is fully depleted.
        if (!localFlushHeaders) {
            lock.lock();
            try {
                localFlushHeaders = (connection.writeBytesTotal >= connection.writeBytesMaximum);
            } finally {
                lock.unlock();
            }
        }

        connection.writeHeaders(id, outFinished, responseHeaders);

        if (localFlushHeaders) {
            connection.flush();
        }
    }

    /**
     * Only used in server mode
     */
    void enqueueTrailers(final @NonNull Headers trailers) {
        assert trailers != null;

        lock.lock();
        try {
            if (writer.finished) {
                throw new IllegalStateException("already finished");
            }
            if (trailers.isEmpty()) {
                throw new IllegalArgumentException("trailers.size() == 0");
            }
            this.writer.trailers = trailers;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Abnormally terminate this stream. This blocks until the {@code RST_STREAM} frame has been transmitted.
     */
    public void close(final @NonNull ErrorCode statusCode, final @Nullable JayoException errorException) {
        assert statusCode != null;

        if (!closeInternal(statusCode, errorException)) {
            return; // Already closed.
        }
        connection.writeSynReset(id, statusCode);
    }

    /**
     * Abnormally terminate this stream. This enqueues a {@code RST_STREAM} frame and returns immediately.
     */
    void closeLater(final @NonNull ErrorCode errorCode) {
        assert errorCode != null;

        if (!closeInternal(errorCode, null)) {
            return; // Already closed.
        }
        connection.writeSynResetLater(id, errorCode);
    }

    /**
     * @return true if this stream was closed.
     */
    private boolean closeInternal(final @NonNull ErrorCode errorCode, final @Nullable JayoException errorException) {
        assert errorCode != null;

        lock.lock();
        try {
            if (this.errorCode != null) {
                return false;
            }
            this.errorCode = errorCode;
            this.errorException = errorException;
            condition.signalAll();
            if (reader.finished && writer.finished) {
                return false;
            }
        } finally {
            lock.unlock();
        }
        connection.removeStream(id);
        return true;
    }

    void receiveData(final @NonNull Reader source, final int length) {
        assert source != null;
        this.reader.receive(source, length);
    }

    /**
     * Accept headers from the network and store them until the client calls {@link #takeHeaders(boolean)}.
     */
    void receiveHeaders(final @NonNull Headers headers, final boolean inFinished) {
        assert headers != null;

        boolean open;
        lock.lock();
        try {
            if (!hasResponseHeaders ||
                    headers.get(RealBinaryHeader.RESPONSE_STATUS_UTF8) != null ||
                    headers.get(RealBinaryHeader.TARGET_METHOD_UTF8) != null) {
                hasResponseHeaders = true;
                headersQueue.add(headers);
            } else {
                this.reader.trailers = headers;
            }
            if (inFinished) {
                this.reader.finished = true;
            }
            open = isOpen();
            condition.signalAll();
        } finally {
            lock.unlock();
        }
        if (!open) {
            connection.removeStream(id);
        }
    }

    void receiveRstStream(final @NonNull ErrorCode errorCode) {
        assert errorCode != null;

        lock.lock();
        try {
            if (this.errorCode == null) {
                this.errorCode = errorCode;
                condition.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return true if read timeouts should be enforced while reading response headers or body bytes. We always do
     * timeouts in the HTTP server role. For clients, we only do timeouts after the request is transmitted. This is only
     * interesting for duplex calls where the request and response may be interleaved.
     * <p>
     * Read this value only once for each enter/exit pair because its value can change.
     */
    private boolean doReadTimeout() {
        return !connection.client || writer.closed || writer.finished;
    }

    /**
     * {@code delta} will be negative if a settings frame initial window is smaller than the last.
     */
    void addBytesToWriteWindow(final long delta) {
        writeBytesMaximum += delta;
        if (delta > 0L) {
            condition.signalAll();
        }
    }

    private void cancelStreamIfNecessary() {
        boolean open;
        boolean cancel;
        lock.lock();
        try {
            cancel = !reader.finished && reader.closed && (writer.finished || writer.closed);
            open = isOpen();
        } finally {
            lock.unlock();
        }
        if (cancel) {
            // RST this stream to prevent additional data from being sent. This is safe because the input stream is
            // closed (we won't use any further bytes) and the output stream is either finished or closed (so RSTing
            // both streams doesn't cause harm).
            close(ErrorCode.CANCEL, null);
        } else if (!open) {
            connection.removeStream(id);
        }
    }

    /**
     * Like {@link Condition#await()}, but throws a {@link JayoInterruptedIOException} when interrupted instead of the
     * more awkward {@link InterruptedException}.
     */
    private void waitForIo() {
        try {
            condition.await();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt(); // Retain interrupted status.
            throw new JayoInterruptedIOException("Thread was interrupted");
        }
    }

    private void timedOut() {
        closeLater(ErrorCode.CANCEL);
        connection.sendDegradedPingLater();
    }

    private void exitAndThrowIfTimedOut(final AsyncTimeout.@Nullable Node node) {
        if (timeout.exit(node)) {
            throw new JayoTimeoutException(TIMEOUT_MSG);
        }
    }

    @Override
    public @NonNull RawReader getReader() {
        return reader;
    }

    @Override
    public @NonNull RawWriter getWriter() {
        return writer;
    }

    @Override
    public void cancel() {
        closeLater(ErrorCode.CANCEL);
    }

    /**
     * A raw Reader that reads the incoming data frames of a stream. Although this class uses synchronization to safely
     * receive incoming data frames, it is not intended for use by multiple readers.
     */
    final class FramingRawReader implements RawReader {
        /**
         * Maximum number of bytes to buffer before reporting a flow control error.
         */
        private final long maxByteCount;

        /**
         * True if either side has cleanly shut down this stream. We will receive no more bytes beyond those already in
         * the buffer.
         */
        private boolean finished;

        /**
         * Buffer to receive data from the network into. Only accessed by the reader thread.
         */
        private final @NonNull Buffer receiveBuffer = Buffer.create();

        /**
         * Buffer with readable data. Guarded by Http2Stream.lock.
         */
        private final @NonNull Buffer readBuffer = Buffer.create();

        /**
         * Received trailers. Null unless the server has provided trailers. Undefined until the stream is exhausted.
         * Guarded by Http2Stream.lock.
         */
        private @Nullable Headers trailers;

        /**
         * True if the caller has closed this stream.
         */
        private boolean closed = false;

        private FramingRawReader(final long maxByteCount, final boolean finished) {
            this.maxByteCount = maxByteCount;
            this.finished = finished;
        }

        @Override
        public long readAtMostTo(final @NonNull Buffer destination, final long byteCount) {
            assert destination != null;

            while (true) {
                var tryAgain = false;
                var readBytesDelivered = -1L;
                JayoException errorExceptionToDeliver = null;

                // 1. Decide what to do in a lock-protected block.

                lock.lock();
                try {
                    final var doReadTimeout = doReadTimeout();
                    AsyncTimeout.Node node = null;
                    if (doReadTimeout) {
                        node = timeout.enter(readTimeoutNanos);
                    }
                    try {
                        if (errorCode != null && !finished) {
                            // Prepare to deliver an error.
                            errorExceptionToDeliver = (errorException != null)
                                    ? errorException
                                    : new JayoStreamResetException(errorCode);
                        }

                        if (closed) {
                            throw new JayoException("stream closed");
                        }

                        if (readBuffer.bytesAvailable() > 0L) {
                            // Prepare to read bytes. Start by moving them to the caller's buffer.
                            readBytesDelivered = readBuffer
                                    .readAtMostTo(destination, Math.min(byteCount, readBuffer.bytesAvailable()));
                            readBytes.update(readBytesDelivered, 0L);

                            final var unacknowledgedBytesRead = readBytes.getUnacknowledged();
                            if (errorExceptionToDeliver == null &&
                                    unacknowledgedBytesRead >= connection.jayoHttpSettings.initialWindowSize() / 2) {
                                // Flow control: notify the peer that we're ready for more data! Only send a
                                // WINDOW_UPDATE if the stream isn't in error.
                                connection.writeWindowUpdateLater(id, unacknowledgedBytesRead);
                                readBytes.update(0L, unacknowledgedBytesRead);
                            }
                        } else if (!finished && errorExceptionToDeliver == null) {
                            // Nothing to do. Wait until that changes, then try again.
                            waitForIo();
                            tryAgain = true;
                        }
                    } finally {
                        if (doReadTimeout) {
                            exitAndThrowIfTimedOut(node);
                        }
                    }
                } finally {
                    lock.unlock();
                }
                connection.flowControlListener.receivingStreamWindowChanged(id, readBytes, readBuffer.bytesAvailable());

                // 2. Do it outside the lock-protected block and timeout.

                if (tryAgain) {
                    continue;
                }

                if (readBytesDelivered != -1L) {
                    return readBytesDelivered;
                }

                if (errorExceptionToDeliver != null) {
                    // We defer throwing the exception until now so that we can refill the connection flow-control
                    // window. This is necessary because we don't transmit window updates until the application reads
                    // the data. If we throw this before updating the connection flow-control window, we risk having it
                    // go to 0, preventing the server from sending data.
                    throw errorExceptionToDeliver;
                }

                return -1L; // This source is exhausted.
            }
        }

        private void updateConnectionFlowControl(final long read) {
            connection.updateConnectionFlowControl(read);
        }

        /**
         * Accept bytes on the connection's reader thread. This function avoids holding locks while it performs blocking
         * reads for the incoming bytes.
         */
        private void receive(final @NonNull Reader source, final long byteCount) {
            assert source != null;

            var remaining = byteCount;
            while (remaining > 0L) {
                boolean finished;
                boolean flowControlError;
                lock.lock();
                try {
                    finished = this.finished;
                    flowControlError = (remaining + readBuffer.bytesAvailable()) > maxByteCount;
                } finally {
                    lock.unlock();
                }

                // If the peer sends more data than we can handle, discard it and close the connection.
                if (flowControlError) {
                    source.skip(remaining);
                    closeLater(ErrorCode.FLOW_CONTROL_ERROR);
                    return;
                }

                // Discard data received after the stream is finished. It's probably a benign race.
                if (finished) {
                    source.skip(remaining);
                    return;
                }

                // Fill the receive buffer without holding any locks.
                final var read = source.readAtMostTo(receiveBuffer, remaining);
                if (read == -1L) {
                    throw new JayoEOFException();
                }
                remaining -= read;

                // Move the received data to the read buffer to the reader can read it. If this source has been closed
                // since this read began, we must discard the incoming data and tell the connection we've done so.
                lock.lock();
                try {
                    if (closed) {
                        receiveBuffer.clear();
                    } else {
                        final var wasEmpty = (readBuffer.bytesAvailable() == 0L);
                        readBuffer.writeAllFrom(receiveBuffer);
                        if (wasEmpty) {
                            condition.signalAll();
                        }
                    }
                } finally {
                    lock.unlock();
                }
            }

            // Update the connection flow control, as this is a shared resource.
            // Even if our stream doesn't need more data, others might.
            // But delay updating the stream flow control until that stream has been consumed
            updateConnectionFlowControl(byteCount);

            // Notify that buffer size changed
            connection.flowControlListener.receivingStreamWindowChanged(id, readBytes, readBuffer.bytesAvailable());
        }

        @Override
        public void close() {
            long bytesDiscarded;
            lock.lock();
            try {
                closed = true;
                bytesDiscarded = readBuffer.bytesAvailable();
                readBuffer.clear();
                condition.signalAll(); // TODO(jwilson): Unnecessary?
            } finally {
                lock.unlock();
            }
            if (bytesDiscarded > 0L) {
                updateConnectionFlowControl(bytesDiscarded);
            }
            cancelStreamIfNecessary();
        }
    }

    /**
     * A raw Writer that writes outgoing data frames of a stream. This class is not thread safe.
     */
    final class FramingRawWriter implements RawWriter {
        /**
         * True if either side has cleanly shut down this stream. We shall send no more bytes.
         */
        private boolean finished;

        /**
         * Buffer of outgoing data. This class batches writes of small writes into this sink as larges frames written to
         * the outgoing connection. Batching saves the (small) framing overhead.
         */
        private final @NonNull Buffer sendBuffer = Buffer.create();

        /**
         * Trailers to send at the end of the stream.
         */
        private @Nullable Headers trailers = null;

        private boolean closed = false;

        private FramingRawWriter(final boolean finished) {
            this.finished = finished;
        }

        @Override
        public void writeFrom(final @NonNull Buffer source, final long byteCount) {
            assert source != null;

            sendBuffer.writeFrom(source, byteCount);
            while (sendBuffer.bytesAvailable() >= EMIT_BUFFER_SIZE) {
                emitFrame(false);
            }
        }

        /**
         * Emit a single data frame to the connection. The frame's size be limited by this stream's write window. This
         * method will block until the write window is nonempty.
         */
        private void emitFrame(final boolean outFinishedOnLastFrame) {
            long toWrite;
            boolean outFinished;
            lock.lock();
            try {
                final var node = timeout.enter(writeTimeoutNanos);
                try {
                    while (writeBytesTotal >= writeBytesMaximum &&
                            !finished &&
                            !closed &&
                            errorCode == null) {
                        waitForIo(); // Wait until we receive a WINDOW_UPDATE for this stream.
                    }
                } finally {
                    exitAndThrowIfTimedOut(node);
                }

                checkOutNotClosed(); // Kick out if the stream was reset or closed while waiting.
                toWrite = Math.min(writeBytesMaximum - writeBytesTotal, sendBuffer.bytesAvailable());
                writeBytesTotal += toWrite;
                outFinished = outFinishedOnLastFrame && (toWrite == sendBuffer.bytesAvailable());
            } finally {
                lock.unlock();
            }

            final var node = timeout.enter(writeTimeoutNanos);
            try {
                connection.writeData(id, outFinished, sendBuffer, toWrite);
            } finally {
                exitAndThrowIfTimedOut(node);
            }
        }

        @Override
        public void flush() {
            lock.lock();
            try {
                checkOutNotClosed();
            } finally {
                lock.unlock();
            }
            // TODO(jwilson): flush the connection?!
            while (sendBuffer.bytesAvailable() > 0L) {
                emitFrame(false);
                connection.flush();
            }
        }

        private void checkOutNotClosed() {
            if (closed) {
                throw new JayoException("stream closed");
            } else if (finished) {
                throw new JayoException("stream finished");
            } else if (errorCode != null) {
                throw (errorException != null) ? errorException : new JayoStreamResetException(errorCode);
            }
        }

        @Override
        public void close() {
            boolean outFinished;
            lock.lock();
            try {
                if (closed) {
                    return;
                }
                outFinished = (errorCode == null);
            } finally {
                lock.unlock();
            }
            if (!finished) {
                // We have 0 or more frames of data, and 0 or more frames of trailers. We need to send at
                // least one frame with the END_STREAM flag set. That must be the last frame, and the
                // trailers must be sent after all of the data.
                final var hasData = sendBuffer.bytesAvailable() > 0L;
                final var hasTrailers = (trailers != null);
                if (hasTrailers) {
                    while (sendBuffer.bytesAvailable() > 0L) {
                        emitFrame(false);
                    }
                    connection.writeHeaders(id, outFinished, toBinaryHeaderList(trailers));
                } else if (hasData) {
                    while (sendBuffer.bytesAvailable() > 0L) {
                        emitFrame(true);
                    }
                } else if (outFinished) {
                    connection.writeData(id, true, null, 0L);
                }
            }
            lock.lock();
            try {
                closed = true;
                condition.signalAll(); // Because doReadTimeout() may have changed.
            } finally {
                lock.unlock();
            }
            connection.flush();
            cancelStreamIfNecessary();
        }
    }

    private static @NonNull List<@NonNull RealBinaryHeader> toBinaryHeaderList(final @NonNull Headers headers) {
        assert headers != null;
        return headers.stream()
                .map(header -> new RealBinaryHeader(header.name(), header.value()))
                .toList();
    }
}
