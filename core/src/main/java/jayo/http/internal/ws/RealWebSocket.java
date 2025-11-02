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
import jayo.http.*;
import jayo.http.EventListener;
import jayo.http.internal.RealClientResponse;
import jayo.http.internal.Utils;
import jayo.http.internal.connection.RealCall;
import jayo.http.internal.connection.RealJayoHttpClient;
import jayo.scheduler.ScheduledTaskQueue;
import jayo.scheduler.TaskRunner;
import jayo.tls.Protocol;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

import static jayo.crypto.JdkDigest.SHA_1;
import static jayo.http.internal.Utils.JAYO_HTTP_NAME;
import static jayo.http.internal.ws.WebSocketProtocol.*;

public final class RealWebSocket implements WebSocket, WebSocketReader.FrameCallback {

    private static final List<Protocol> ONLY_HTTP1 = List.of(Protocol.HTTP_1_1);

    /**
     * The maximum number of bytes to enqueue. Rather than enqueueing beyond this limit, we tear down the web socket!
     * It's possible that we're writing faster than the peer can read.
     */
    private static final long MAX_QUEUE_SIZE = 16L * 1024 * 1024; // 16 MiB.

    /**
     * The maximum amount of time after the client calls {@link #close(short, String)} to wait for a graceful shutdown.
     * If the server doesn't respond, the web socket will be canceled.
     */
    public static final @NonNull Duration CANCEL_AFTER_CLOSE_TIMEOUT = Duration.ofSeconds(60);

    /**
     * The smallest message that will be compressed. We use 1024 because smaller messages already fit comfortably within
     * a single ethernet packet (1500 bytes) even with framing overhead.
     * <p>
     * For tests this must be big enough to realize real compression on test messages like 'aaaaaaaaaa...'. Our tests
     * check if compression was applied just by looking at the size if the inbound buffer.
     */
    public static final long DEFAULT_MINIMUM_DEFLATE_SIZE = 1024L;

    /**
     * The application's original request unadulterated by web socket headers.
     */
    private final @NonNull ClientRequest originalRequest;
    final @NonNull WebSocketListener listener;
    private final @NonNull Random random;
    private final @NonNull Duration pingInterval;
    /**
     * For clients this is initially null and will be assigned to the agreed-upon extensions. For servers, it should be
     * the agreed-upon extensions immediately.
     */
    private WebSocketExtensions extensions;
    /**
     * If compression is negotiated, outbound messages of this size and larger will be compressed.
     */
    private final long minimumDeflateSize;
    private final @NonNull Duration webSocketCloseTimeout;

    private final @NonNull String key;
    /**
     * Non-null for client web sockets. These can be canceled.
     */
    @Nullable Call call = null;
    /**
     * This task processes the outgoing queues. Call {@link #runWriter} to after enqueueing.
     */
    private @Nullable LongSupplier writerTask = null;

    /**
     * Null until this web socket is connected. Only accessed by the reader thread.
     */
    private WebSocketReader reader = null;

    // All mutable web socket state properties are guarded by {@link #lock}.
    private final @NonNull Lock lock = new ReentrantLock();

    /**
     * Null until this web socket is connected. Note that messages may be enqueued before that.
     */
    private WebSocketWriter writer = null;

    /**
     * Used for writes, pings, and close timeouts.
     */
    private final @NonNull ScheduledTaskQueue taskQueue;

    /**
     * Names this web socket for observability and debugging.
     */
    private @Nullable String name = null;

    /**
     * The socket that carries this web socket. This is canceled when the web socket fails.
     */
    private @Nullable Socket socket = null;

    /**
     * Outgoing pongs in the order they should be written.
     */
    private final Queue<ByteString> pongQueue = new ArrayDeque<>();

    /**
     * Outgoing messages and close frames in the order they should be written.
     */
    private final Queue<Object> messageAndCloseQueue = new ArrayDeque<>();

    /**
     * The total size in bytes of enqueued but not yet transmitted messages.
     */
    private long queueByteSize = 0L;

    /**
     * True if we've enqueued a close frame. No further message frames will be enqueued.
     */
    private boolean enqueuedClose = false;

    /**
     * The close code from the peer, or -1 if this web socket has not yet read a close frame.
     */
    private int receivedCloseCode = -1;

    /**
     * The close reason from the peer, or null if this web socket has not yet read a close frame.
     */
    private String receivedCloseReason = null;

    /**
     * True if this web socket failed and the listener has been notified.
     */
    private boolean failed = false;

    /**
     * Total number of pings sent by this web socket.
     */
    private int sentPingCount = 0;

    /**
     * Total number of pings received by this web socket.
     */
    private int receivedPingCount = 0;

    /**
     * Total number of pongs received by this web socket.
     */
    private int receivedPongCount = 0;

    /**
     * True if we have sent a ping that is still awaiting a reply.
     */
    private boolean awaitingPong = false;

    public RealWebSocket(final @NonNull TaskRunner taskRunner,
                         final @NonNull ClientRequest originalRequest,
                         final @NonNull WebSocketListener listener,
                         final @NonNull Random random,
                         final @NonNull Duration pingInterval,
                         final @Nullable WebSocketExtensions extensions,
                         final long minimumDeflateSize,
                         final @NonNull Duration webSocketCloseTimeout) {
        assert taskRunner != null;
        assert originalRequest != null;
        assert listener != null;
        assert random != null;
        assert pingInterval != null;
        assert webSocketCloseTimeout != null;

        if (!"GET".equals(originalRequest.getMethod())) {
            throw new IllegalArgumentException("Request must be GET: " + originalRequest.getMethod());
        }

        this.originalRequest = originalRequest;
        this.listener = listener;
        this.random = random;
        this.pingInterval = pingInterval;
        this.extensions = extensions;
        this.minimumDeflateSize = minimumDeflateSize;
        this.webSocketCloseTimeout = webSocketCloseTimeout;

        final var bytes = new byte[16];
        random.nextBytes(bytes);
        this.key = ByteString.of(bytes).base64();
        this.taskQueue = taskRunner.newScheduledQueue();
    }

    @Override
    public @NonNull ClientRequest request() {
        return originalRequest;
    }

    @Override
    public long queueByteSize() {
        lock.lock();
        try {
            return queueByteSize;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void cancel() {
        assert call != null;
        call.cancel();
    }

    public void connect(final @NonNull JayoHttpClient client) {
        assert client != null;

        if (originalRequest.header("Sec-WebSocket-Extensions") != null) {
            failWebSocket(new JayoProtocolException("Request header not permitted: 'Sec-WebSocket-Extensions'"),
                    null, false);
            return;
        }

        final var webSocketClient = client.newBuilder()
                .eventListener(EventListener.NONE)
                .protocols(ONLY_HTTP1)
                .build();
        final var request = originalRequest.newBuilder()
                .header("Upgrade", "websocket")
                .header("Connection", "Upgrade")
                .header("Sec-WebSocket-Key", key)
                .header("Sec-WebSocket-Version", "13")
                .header("Sec-WebSocket-Extensions", "permessage-deflate")
                .build();
        call = new RealCall((RealJayoHttpClient) webSocketClient, request, true);
        call.enqueue(new Callback() {
            @Override
            public void onResponse(final @NonNull Call call, final @NonNull ClientResponse response) {
                assert call != null;
                assert response != null;

                final var realResponse = (RealClientResponse) response;
                final RawSocket socket;
                try {
                    socket = checkUpgradeSuccess(realResponse);
                } catch (JayoException je) {
                    failWebSocket(je, realResponse, false);
                    Utils.closeQuietly(realResponse);
                    if (realResponse.getSocket() != null) {
                        Utils.closeQuietly(realResponse.getSocket().getWriter());
                        Utils.closeQuietly(realResponse.getSocket().getReader());
                    }
                    return;
                }

                // Apply the extensions. If they're unacceptable initiate a graceful shut down.
                // TODO(jwilson): Listeners should get onFailure() instead of onClosing() + onClosed(1010).
                final var extensions = WebSocketExtensions.parse(response.getHeaders());
                RealWebSocket.this.extensions = extensions;
                if (!isValid(extensions)) {
                    lock.lock();
                    try {
                        messageAndCloseQueue.clear(); // Don't transmit any messages.
                        close((short) 1010, "unexpected Sec-WebSocket-Extensions in response header");
                    } finally {
                        lock.unlock();
                    }
                }

                // Process all web socket messages.
                final var name = JAYO_HTTP_NAME + " WebSocket " + request.getUrl().redact();
                initReaderAndWriter(
                        name,
                        Jayo.buffer(socket),
                        true
                );
                loopReader(response);
            }

            private @NonNull RawSocket checkUpgradeSuccess(final @NonNull RealClientResponse response) {
                assert response != null;

                if (response.getStatusCode() != 101) {
                    throw new JayoProtocolException(
                            "Expected HTTP 101 response but was '" + response.getStatusCode() +
                                    " " + response.getStatusMessage() + "'"
                    );
                }

                final var headerConnection = response.header("Connection");
                if (!"Upgrade".equalsIgnoreCase(headerConnection)) {
                    throw new JayoProtocolException(
                            "Expected 'Connection' header value 'Upgrade' but was '" + headerConnection + "'");
                }

                final var headerUpgrade = response.header("Upgrade");
                if (!"websocket".equalsIgnoreCase(headerUpgrade)) {
                    throw new JayoProtocolException(
                            "Expected 'Upgrade' header value 'websocket' but was '" + headerUpgrade + "'");
                }

                final var headerAccept = response.header("Sec-WebSocket-Accept");
                final var acceptExpected = ByteString.encode(key + WebSocketProtocol.ACCEPT_MAGIC)
                        .hash(SHA_1)
                        .base64();
                if (!acceptExpected.equals(headerAccept)) {
                    throw new JayoProtocolException("Expected 'Sec-WebSocket-Accept' header value '" + acceptExpected +
                            "' but was '" + headerAccept + "'");
                }

                if (response.getSocket() == null) {
                    throw new JayoProtocolException("Web Socket socket missing: bad interceptor?");
                }
                return response.getSocket();
            }

            private static boolean isValid(final @NonNull WebSocketExtensions extensions) {
                assert extensions != null;

                // If the server returned parameters we don't understand, fail the web socket.
                if (extensions.unknownValues) {
                    return false;
                }

                // If the server returned a value for client_max_window_bits, fail the web socket.
                if (extensions.clientMaxWindowBits != null) {
                    return false;
                }

                // If the server returned an illegal server_max_window_bits, fail the web socket.
                if (extensions.serverMaxWindowBits != null &&
                        (extensions.serverMaxWindowBits < 8 || extensions.serverMaxWindowBits > 15)) {
                    return false;
                }

                // Success.
                return true;
            }

            @Override
            public void onFailure(final @NonNull Call call, final @NonNull JayoException je) {
                failWebSocket(je, null, false);
            }
        });
    }

    void initReaderAndWriter(final @NonNull String name,
                             final @NonNull Socket socket,
                             final boolean client) {
        final var extensions = this.extensions;
        lock.lock();
        try {
            this.name = name;
            this.socket = socket;
            this.writer = new WebSocketWriter(
                    client,
                    socket.getWriter(),
                    random,
                    extensions.perMessageDeflate,
                    extensions.noContextTakeover(client),
                    minimumDeflateSize
            );
            this.writerTask = new WriterTask();
            if (!pingInterval.equals(Duration.ZERO)) {
                final var pingIntervalNanos = pingInterval.toNanos();
                taskQueue.schedule(name + " ping", pingIntervalNanos, () -> {
                    writePingFrame();
                    return pingIntervalNanos;
                });
            }
            if (!messageAndCloseQueue.isEmpty()) {
                runWriter(); // Send messages that were enqueued before we were connected.
            }
        } finally {
            lock.unlock();
        }

        this.reader = new WebSocketReader(
                client,
                socket.getReader(),
                this,
                extensions.perMessageDeflate,
                extensions.noContextTakeover(!client)
        );
    }

    /**
     * Receive frames until there are no more. Invoked only by the reader thread.
     */
    public void loopReader(final @NonNull ClientResponse response) {
        assert response != null;

        try {
            listener.onOpen(this, response);
            while (receivedCloseCode == -1) {
                // This method call results in one or more onRead* methods being called on this thread.
                reader.processNextFrame();
            }
        } catch (Exception e) {
            failWebSocket(e, null, false);
        } finally {
            finishReader();
        }
    }

    /**
     * For testing: receive a single frame and return true if there are more frames to read. Invoked only by the reader
     * thread.
     */
    boolean processNextFrame() {
        try {
            reader.processNextFrame();
            return receivedCloseCode == -1;
        } catch (RuntimeException e) {
            failWebSocket(e, null, false);
            return false;
        }
    }

    /**
     * Clean up and publish the necessary close events when the reader is done. Invoked only by the reader thread.
     */
    void finishReader() {
        final int code;
        final String reason;
        final boolean sendOnClosed;
        final WebSocketReader readerToClose;

        lock.lock();
        try {
            code = receivedCloseCode;
            reason = receivedCloseReason;

            readerToClose = reader;
            reader = null;

            if (enqueuedClose && messageAndCloseQueue.isEmpty()) {
                // Close the writer on the writer's thread.
                final WebSocketWriter writerToClose = this.writer;
                if (writerToClose != null) {
                    this.writer = null;
                    taskQueue.execute(name + " writer close", false,
                            () -> Utils.closeQuietly(writerToClose));
                }

                this.taskQueue.shutdown();
            }

            sendOnClosed = !failed && writer == null && receivedCloseCode != -1;
        } finally {
            lock.unlock();
        }

        if (sendOnClosed) {
            listener.onClosed(this, code, reason);
        }

        if (readerToClose != null) {
            Utils.closeQuietly(readerToClose);
        }
    }

    /**
     * For testing: force this web socket to release its threads.
     */
    void tearDown() throws InterruptedException {
        taskQueue.shutdown();
        taskQueue.idleLatch().await(10, TimeUnit.SECONDS);
    }

    int sentPingCount() {
        lock.lock();
        try {
            return sentPingCount;
        } finally {
            lock.unlock();
        }
    }

    int receivedPingCount() {
        lock.lock();
        try {
            return receivedPingCount;
        } finally {
            lock.unlock();
        }
    }

    int receivedPongCount() {
        lock.lock();
        try {
            return receivedPongCount;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void onReadMessage(final @NonNull String text) {
        Objects.requireNonNull(text);
        listener.onMessage(this, text);
    }

    @Override
    public void onReadMessage(final @NonNull ByteString bytes) {
        Objects.requireNonNull(bytes);
        listener.onMessage(this, bytes);
    }

    @Override
    public void onReadPing(final @NonNull ByteString payload) {
        Objects.requireNonNull(payload);

        lock.lock();
        try {
            // Don't respond to pings after we've failed or sent the close frame.
            if (failed || (enqueuedClose && messageAndCloseQueue.isEmpty())) {
                return;
            }

            pongQueue.add(payload);
            runWriter();
            receivedPingCount++;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void onReadPong(final @NonNull ByteString payload) {
        Objects.requireNonNull(payload);

        lock.lock();
        try {
            // This API doesn't expose pings.
            receivedPongCount++;
            awaitingPong = false;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void onReadClose(final int code, final @NonNull String reason) {
        Objects.requireNonNull(reason);
        if (code == -1) {
            throw new IllegalArgumentException("Failed requirement.");
        }

        lock.lock();
        try {
            if (receivedCloseCode != -1) {
                throw new IllegalStateException("already closed");
            }
            receivedCloseCode = code;
            receivedCloseReason = reason;
        } finally {
            lock.unlock();
        }

        listener.onClosing(this, code, reason);
    }

    // Writer methods to enqueue frames. They'll be sent asynchronously by the writer thread.

    @Override
    public boolean send(final @NonNull String text) {
        Objects.requireNonNull(text);
        return send(ByteString.encode(text), OPCODE_TEXT);
    }

    @Override
    public boolean send(final @NonNull ByteString bytes) {
        Objects.requireNonNull(bytes);
        return send(bytes, OPCODE_BINARY);
    }

    private boolean send(final @NonNull ByteString data, final byte formatOpcode) {
        assert data != null;

        lock.lock();
        try {
            // Don't send new frames after we've failed or enqueued a close frame.
            if (failed || enqueuedClose) {
                return false;
            }

            // If this frame overflows the buffer, reject it and close the web socket.
            if (queueByteSize + data.byteSize() > MAX_QUEUE_SIZE) {
                close(CLOSE_CLIENT_GOING_AWAY, null);
                return false;
            }

            // Enqueue the message frame.
            queueByteSize += data.byteSize();
            messageAndCloseQueue.add(new Message(formatOpcode, data));
            runWriter();
            return true;
        } finally {
            lock.unlock();
        }
    }

    boolean pong(final @NonNull ByteString payload) {
        assert payload != null;

        lock.lock();
        try {
            // Don't send pongs after we've failed or sent the close frame.
            if (failed || (enqueuedClose && messageAndCloseQueue.isEmpty())) {
                return false;
            }

            pongQueue.add(payload);
            runWriter();
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean close(final short code, final @Nullable String reason) {
        return close(code, reason, webSocketCloseTimeout);
    }

    boolean close(final short code,
                  final @Nullable String reason,
                  final @NonNull Duration cancelAfterClose) {
        assert cancelAfterClose != null;

        lock.lock();
        try {
            validateCloseCode(code);

            ByteString reasonBytes = null;
            if (reason != null) {
                reasonBytes = ByteString.encode(reason);
                if (reasonBytes.byteSize() > CLOSE_MESSAGE_MAX) {
                    throw new IllegalArgumentException("reason.size() > " + CLOSE_MESSAGE_MAX + ": " + reason);
                }
            }

            if (failed || enqueuedClose) {
                return false;
            }

            // Immediately prevent further frames from being enqueued.
            enqueuedClose = true;

            // Enqueue the close frame.
            messageAndCloseQueue.add(new Close(code, reasonBytes, cancelAfterClose));
            runWriter();
            return true;
        } finally {
            lock.unlock();
        }
    }

    private void runWriter() {
        final var writerTask = this.writerTask;
        if (writerTask != null) {
            taskQueue.schedule(name + " writer", 0L, writerTask);
        }
    }

    /**
     * Attempts to remove a single frame from a queue and send it. This prefers to write urgent pongs before less urgent
     * messages and close frames. For example, it's possible that a caller will enqueue messages followed by pongs, but
     * this sends pongs followed by messages. Pongs are always written in the order they were enqueued.
     * <p>
     * If a frame cannot be sent - because there are none enqueued or because the web socket is not connected - this
     * does nothing and returns false. Otherwise, this returns true, and the caller should immediately invoke this
     * method again until it returns false.
     * <p>
     * This method may only be invoked by the writer thread. There may be only thread invoking this method at a time.
     */
    private boolean writeOneFrame() {
        final WebSocketWriter writer;
        final ByteString pong;
        Object messageOrClose = null;
        var receivedCloseCode = -1;
        String receivedCloseReason = null;
        var sendOnClosed = false;
        WebSocketWriter writerToClose = null;

        lock.lock();
        try {
            if (failed) {
                return false; // Failed web socket.
            }

            writer = this.writer;
            pong = pongQueue.poll();
            if (pong == null) {
                messageOrClose = messageAndCloseQueue.poll();
                if (messageOrClose instanceof Close close) {
                    receivedCloseCode = this.receivedCloseCode;
                    receivedCloseReason = this.receivedCloseReason;
                    if (receivedCloseCode != -1) {
                        writerToClose = this.writer;
                        this.writer = null;
                        sendOnClosed = writerToClose != null && reader == null;
                        this.taskQueue.shutdown();
                    } else {
                        // When we request a graceful close also schedule a cancel of the web socket.
                        final var cancelAfterCloseNanos = close.cancelAfterClose.toNanos();
                        taskQueue.schedule(name + " cancel", cancelAfterCloseNanos,
                                () -> {
                                    cancel();
                                    return -1L;
                                });
                    }
                } else if (messageOrClose == null) {
                    return false; // The queue is exhausted.
                }
            }
        } finally {
            lock.unlock();
        }

        try {
            if (pong != null) {
                writer.writePong(pong);
            } else if (messageOrClose instanceof Message message) {
                writer.writeMessageFrame(message.formatOpcode, message.data);
                lock.lock();
                try {
                    queueByteSize -= message.data.byteSize();
                } finally {
                    lock.unlock();
                }
            } else if (messageOrClose instanceof Close close) {
                assert writer != null;
                writer.writeClose(close.code, close.reason);

                // We closed the writer: now both reader and writer are closed.
                if (sendOnClosed) {
                    listener.onClosed(this, receivedCloseCode, receivedCloseReason);
                }
            } else {
                throw new AssertionError();
            }

            return true;
        } finally {
            if (writerToClose != null) {
                Utils.closeQuietly(writerToClose);
            }
        }
    }

    private void writePingFrame() {
        WebSocketWriter writer;
        int failedPing;
        lock.lock();
        try {
            if (failed) {
                return;
            }
            writer = this.writer;
            if (writer == null) {
                return;
            }
            failedPing = awaitingPong ? sentPingCount : -1;
            sentPingCount++;
            awaitingPong = true;
        } finally {
            lock.unlock();
        }

        if (failedPing != -1) {
            failWebSocket(
                    new JayoTimeoutException(
                            "sent ping but didn't receive pong within " + pingInterval.toMillis() + "ms (after " +
                                    (failedPing - 1) + " successful ping/pongs)"),
                    null,
                    true);
            return;
        }

        try {
            writer.writePing(ByteString.EMPTY);
        } catch (JayoException je) {
            failWebSocket(je, null, true);
        }
    }

    private void failWebSocket(final @NonNull Exception e,
                               final @Nullable ClientResponse response,
                               final boolean isWriter) {
        Socket socketToCancel;
        WebSocketWriter writerToClose;
        lock.lock();
        try {
            if (failed) {
                return; // Already failed.
            }
            failed = true;

            socketToCancel = this.socket;

            writerToClose = this.writer;
            this.writer = null;

            if (!isWriter && writerToClose != null) {
                // If the caller isn't the writer thread, get that thread to close the writer.
                taskQueue.execute(name + " writer close", false, () ->
                        Utils.closeQuietly(writerToClose));
            }

            taskQueue.shutdown();
        } finally {
            lock.unlock();
        }

        try {
            listener.onFailure(this, e, response);
        } finally {
            if (socketToCancel != null) {
                socketToCancel.cancel();
            }

            // If the caller is the writer thread, close it on this thread.
            if (isWriter && writerToClose != null) {
                Utils.closeQuietly(writerToClose);
            }
        }
    }

    private static final class Message {
        private final byte formatOpcode;
        private final @NonNull ByteString data;

        private Message(final byte formatOpcode, final @NonNull ByteString data) {
            assert data != null;

            this.formatOpcode = formatOpcode;
            this.data = data;
        }
    }

    private static final class Close {
        private final short code;
        private final @Nullable ByteString reason;
        private final @NonNull Duration cancelAfterClose;

        private Close(final short code,
                      final @Nullable ByteString reason,
                      final @NonNull Duration cancelAfterClose) {
            this.code = code;
            this.reason = reason;
            this.cancelAfterClose = cancelAfterClose;
        }
    }

    private final class WriterTask implements LongSupplier {
        @Override
        public long getAsLong() {
            try {
                if (writeOneFrame()) {
                    return 0L;
                }
            } catch (JayoException je) {
                failWebSocket(je, null, true);
            }
            return -1L;
        }
    }
}
