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

import jayo.JayoException;
import jayo.http.*;
import jayo.network.Proxy;
import jayo.tls.Handshake;
import jayo.tls.Protocol;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

public final class PrintEvents {
    private final JayoHttpClient client = JayoHttpClient.builder()
            .eventListener(new PrintingEventListener())
            .build();

    public void run() {
        ClientRequest request = ClientRequest.builder()
                .url("https://raw.githubusercontent.com/jayo-projects/jayo-http/main/samples/src/main/resources/jayo-http.txt")
                .get();
        System.out.println("REQUEST 1 (new connection)");
        try (ClientResponse response = client.newCall(request).execute()) {
            // Consume and discard the response body.
            response.getBody().reader().readByteString();
        }

        System.out.println("REQUEST 2 (pooled connection)");
        try (ClientResponse response = client.newCall(request).execute()) {
            // Consume and discard the response body.
            response.getBody().reader().readByteString();
        }
    }

    public static void main(String... args) {
        new PrintEvents().run();
    }

    private static final class PrintingEventListener extends EventListener {
        long callStartNanos;

        private void printEvent(String name) {
            long nowNanos = System.nanoTime();
            if (name.equals("callStart")) {
                callStartNanos = nowNanos;
            }
            long elapsedNanos = nowNanos - callStartNanos;
            System.out.printf("%.3f %s%n", elapsedNanos / 1000000000d, name);
        }

        @Override
        public void proxySelected(final Call call,
                                  final HttpUrl url,
                                  final Proxy proxy) {
            printEvent("proxySelected");
        }

        @Override
        public void callStart(Call call) {
            printEvent("callStart");
        }

        @Override
        public void dnsStart(Call call, String domainName) {
            printEvent("dnsStart");
        }

        @Override
        public void dnsEnd(Call call, String domainName, List<InetAddress> inetAddressList) {
            printEvent("dnsEnd");
        }

        @Override
        public void connectStart(
                Call call, InetSocketAddress inetSocketAddress, Proxy proxy) {
            printEvent("connectStart");
        }

        @Override
        public void secureConnectStart(Call call) {
            printEvent("secureConnectStart");
        }

        @Override
        public void secureConnectEnd(Call call, Handshake handshake) {
            printEvent("secureConnectEnd");
        }

        @Override
        public void connectEnd(
                Call call, InetSocketAddress inetSocketAddress, Proxy proxy, Protocol protocol) {
            printEvent("connectEnd");
        }

        @Override
        public void connectFailed(Call call, InetSocketAddress inetSocketAddress, Proxy proxy,
                                  Protocol protocol, JayoException je) {
            printEvent("connectFailed");
        }

        @Override
        public void connectionAcquired(Call call, Connection connection) {
            printEvent("connectionAcquired");
        }

        @Override
        public void connectionReleased(Call call, Connection connection) {
            printEvent("connectionReleased");
        }

        @Override
        public void requestHeadersStart(Call call) {
            printEvent("requestHeadersStart");
        }

        @Override
        public void requestHeadersEnd(Call call, ClientRequest request) {
            printEvent("requestHeadersEnd");
        }

        @Override
        public void requestBodyStart(Call call) {
            printEvent("requestBodyStart");
        }

        @Override
        public void requestBodyEnd(Call call, long byteCount) {
            printEvent("requestBodyEnd");
        }

        @Override
        public void requestFailed(Call call, JayoException je) {
            printEvent("requestFailed");
        }

        @Override
        public void responseHeadersStart(Call call) {
            printEvent("responseHeadersStart");
        }

        @Override
        public void responseHeadersEnd(Call call, ClientResponse response) {
            printEvent("responseHeadersEnd");
        }

        @Override
        public void responseBodyStart(Call call) {
            printEvent("responseBodyStart");
        }

        @Override
        public void responseBodyEnd(Call call, long byteCount) {
            printEvent("responseBodyEnd");
        }

        @Override
        public void responseFailed(Call call, JayoException je) {
            printEvent("responseFailed");
        }

        @Override
        public void callEnd(Call call) {
            printEvent("callEnd");
        }

        @Override
        public void callFailed(Call call, JayoException je) {
            printEvent("callFailed");
        }

        @Override
        public void canceled(Call call) {
            printEvent("canceled");
        }
    }
}
