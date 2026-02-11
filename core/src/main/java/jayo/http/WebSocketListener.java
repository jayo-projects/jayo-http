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
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface WebSocketListener {
    /**
     * Invoked when a web socket is enqueued to the dispatcher.
     */
    default void onEnqueued(final @NonNull Call call, @NonNull Dispatcher dispatcher) {
    }

    /**
     * Invoked when a web socket has been accepted by the remote peer and may begin transmitting messages.
     */
    default void onOpen(final @NonNull WebSocket webSocket, final @NonNull ClientResponse response) {
    }

    /**
     * Invoked when a text (type {@code 0x1}) message has been received.
     */
    default void onMessage(final @NonNull WebSocket webSocket, final @NonNull String text) {
    }

    /**
     * Invoked when a binary (type {@code 0x2}) message has been received.
     */
    default void onMessage(final @NonNull WebSocket webSocket, final @NonNull ByteString bytes) {
    }

    /**
     * Invoked when the remote peer has indicated that no more incoming messages will be transmitted.
     */
    default void onClosing(final @NonNull WebSocket webSocket,
                           final int code,
                           final @NonNull String reason) {
    }

    /**
     * Invoked when both peers have indicated that no more messages will be transmitted and the connection has been
     * successfully released. No further calls to this listener will be made.
     */
    default void onClosed(final @NonNull WebSocket webSocket,
                          final int code,
                          final @NonNull String reason
    ) {
    }

    /**
     * Invoked when a web socket has been closed due to an error reading from or writing to the network. Both outgoing
     * and incoming messages may have been lost. No further calls to this listener will be made.
     */
    default void onFailure(final @NonNull WebSocket webSocket,
                           final @NonNull Throwable t,
                           final @Nullable ClientResponse response) {
    }
}
