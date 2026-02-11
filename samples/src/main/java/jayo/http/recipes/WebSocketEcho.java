/*
 * Copyright (c) 2026-present, pull-vert and Jayo contributors.
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

package jayo.http.recipes;

import jayo.bytestring.ByteString;
import jayo.http.*;
import org.jspecify.annotations.NonNull;

import java.time.Duration;

public final class WebSocketEcho implements WebSocketListener {
    private void run() {
        JayoHttpClient client = JayoHttpClient.builder()
                .readTimeout(Duration.ZERO)
                .build();

        ClientRequest request = ClientRequest.builder()
                .url("ws://echo.websocket.org")
                .get();
        client.newWebSocket(request, this);

        // Trigger shutdown of the dispatcher, leaving a few seconds for this asynchronous request to respond if the
        // network is slow.
        client.getDispatcher().shutdown(Duration.ofSeconds(5));
    }

    @Override
    public void onEnqueued(@NonNull Call call, @NonNull Dispatcher dispatcher) {
        System.out.println("Enqueued for async execution");
    }

    @Override
    public void onOpen(WebSocket webSocket, ClientResponse response) {
        webSocket.send("Hello...");
        webSocket.send("...World!");
        webSocket.send(ByteString.decodeHex("deadbeef")); // see https://en.wikipedia.org/wiki/Hexspeak
        webSocket.close((short) 1000, "Goodbye, World!");
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
        System.out.println("MESSAGE: " + text);
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
        System.out.println("MESSAGE: " + bytes.hex());
    }

    @Override
    public void onClosing(WebSocket webSocket, int code, String reason) {
        webSocket.close((short) 1000, null);
        System.out.println("CLOSE: " + code + " " + reason);
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, ClientResponse response) {
        t.printStackTrace();
    }

    public static void main(String... args) {
        new WebSocketEcho().run();
    }
}
