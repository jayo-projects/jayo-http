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

package jayo.http;

import jayo.bytestring.ByteString;
import jayo.http.internal.ws.RealWebSocket;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A non-blocking interface to a web socket. Use the ÷{@linkplain WebSocket.Factory factory} to create instances;
 * usually this is {@link JayoHttpClient}.
 * <h2>Web Socket Lifecycle</h2>
 * Upon normal operation each web socket progresses through a sequence of states:
 * <ul>
 * <li><b>Connecting:</b> the initial state of each web socket. Messages may be enqueued, but they won't be transmitted
 * until the web socket is open.
 * <li><b>Open:</b> the web socket has been accepted by the remote peer and is fully operational. Messages in either
 * direction are enqueued for immediate transmission.
 * <li><b>Closing:</b> one of the peers on the web socket has initiated a graceful shutdown. The web socket will
 * continue to transmit already-enqueued messages but will refuse to enqueue new ones.
 * <li><b>Closed:</b> the web socket has transmitted all of its messages and has received all messages from the peer.
 * </ul>
 * Web sockets may fail due to HTTP upgrade problems, connectivity problems, or if either peer chooses to short-circuit
 * the graceful shutdown process:
 * <ul>
 * <li><b>Canceled:</b> the web socket connection failed. Messages that were successfully enqueued by either peer may
 * not have been transmitted to the other.
 * </ul>
 * Note that the state progression is independent for each peer. Arriving at a gracefully closed state indicates that a
 * peer has sent all of its outgoing messages and received all of its incoming messages. But it does not guarantee that
 * the other peer will successfully receive all of its incoming messages.
 */
public sealed interface WebSocket permits RealWebSocket {
    /**
     * @return the original request that initiated this web socket.
     */
    @NonNull ClientRequest request();

    /**
     * @return the size in bytes of all messages enqueued to be transmitted to the server. This doesn't include framing
     * overhead. If compression is enabled, uncompressed messages size is used to calculate this value. It also doesn't
     * include any bytes buffered by the operating system or network intermediaries. This method returns 0 if no
     * messages are waiting in the queue. It may return a nonzero value after the web socket has been canceled; this
     * indicates that enqueued messages were not transmitted.
     */
    long queueByteSize();

    /**
     * Attempts to enqueue {@code text} to be UTF-8 encoded and sent as the data of a text (type {@code 0x1}) message.
     * <p>
     * This method returns true if the message was enqueued. Messages that would overflow the outgoing message buffer
     * will be rejected and trigger a {@linkplain #close(short, String) graceful shutdown} of this web socket. This method
     * returns false in that case, and in any other case where this web socket is closing, closed, or canceled.
     * <p>
     * This method returns immediately.
     */
    boolean send(final @NonNull String text);

    /**
     * Attempts to enqueue {@code bytes} to be sent as the data of a binary (type {@code 0x2}) message.
     * <p>
     * This method returns true if the message was enqueued. Messages that would overflow the outgoing message buffer
     * (16 MiB) will be rejected and trigger a {@linkplain #close(short, String) graceful shutdown} of this web socket.
     * This method returns false in that case, and in any other case where this web socket is closing, closed, or
     * canceled.
     * <p>
     * This method returns immediately.
     */
    boolean send(final @NonNull ByteString bytes);

    /**
     * Attempts to initiate a graceful shutdown of this web socket. Any already-enqueued messages will be transmitted
     * before the close message is sent, but subsequent calls to {@link #send(String)} or {@link #send(ByteString)} will
     * return false and their messages will not be enqueued.
     * <p>
     * This returns true if a graceful shutdown was initiated by this call. It returns false if a graceful shutdown was
     * already underway or if the web socket is already closed or canceled.
     *
     * @param code   Status code as defined by <a href="https://tools.ietf.org/html/rfc6455#section-7.4">
     *               Section 7.4 of RFC 6455</a>.
     * @param reason Reason for shutting down, no longer than 123 bytes of UTF-8 encoded data (<b>not</b> characters) or
     *               null.
     * @throws IllegalArgumentException if {@code code} is invalid or {@code reason} is too long.
     */
    boolean close(final short code, final @Nullable String reason);

    /**
     * Immediately and violently release resources held by this web socket, discarding any enqueued messages. This does
     * nothing if the web socket has already been closed or canceled.
     */
    void cancel();

    interface Factory {
        /**
         * Creates a new web socket and immediately returns it. Creating a web socket initiates an asynchronous process
         * to connect the socket by using {@code request}. Once that succeeds or fails, {@code listener} will be
         * notified. The caller must either close or cancel the returned web socket when it is no longer in use.
         */
        @NonNull
        WebSocket newWebSocket(final @NonNull ClientRequest request, final @NonNull WebSocketListener listener);
    }
}
