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

package jayo.http.logging.internal;

import jayo.JayoException;
import jayo.http.*;
import jayo.http.logging.HttpLoggingInterceptor;
import jayo.http.logging.LoggingEventListener;
import jayo.network.Proxy;
import jayo.tls.Handshake;
import jayo.tls.Protocol;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;

public final class RealLoggingEventListener extends LoggingEventListener {
    private final HttpLoggingInterceptor.@NonNull Logger logger;
    private long startNs = 0L;

    public RealLoggingEventListener(final HttpLoggingInterceptor.@NonNull Logger logger) {
        assert logger != null;
        this.logger = logger;
    }

    @Override
    public void callStart(final @NonNull Call call) {
        startNs = System.nanoTime();

        logWithTime("callStart: " + call.request());
    }

    @Override
    public void dispatcherQueueStart(final @NonNull Call call, final @NonNull Dispatcher dispatcher) {
        logWithTime("dispatcherQueueStart: " + call + " queuedCallsCount=" + dispatcher.queuedCallsCount());
    }

    @Override
    public void dispatcherQueueEnd(final @NonNull Call call, final @NonNull Dispatcher dispatcher) {
        logWithTime("dispatcherQueueEnd: " + call + " queuedCallsCount=" + dispatcher.queuedCallsCount());
    }

    @Override
    public void proxySelected(final @NonNull Call call,
                              final @NonNull HttpUrl url,
                              final @Nullable Proxy proxy) {
        logWithTime("proxySelected: " + url + " proxy=" + proxy);
    }

    @Override
    public void dnsStart(final @NonNull Call call, final @NonNull String domainName) {
        logWithTime("dnsStart: " + domainName);
    }

    @Override
    public void dnsEnd(final @NonNull Call call,
                       final @NonNull String domainName,
                       final @NonNull List<InetAddress> inetAddressList) {
        logWithTime("dnsEnd: " + inetAddressList);
    }

    @Override
    public void connectStart(final @NonNull Call call,
                             final @NonNull InetSocketAddress inetSocketAddress,
                             final @Nullable Proxy proxy) {
        logWithTime("connectStart: " + inetSocketAddress + " proxy=" + proxy);
    }

    @Override
    public void secureConnectStart(final @NonNull Call call) {
        logWithTime("secureConnectStart");
    }

    @Override
    public void secureConnectEnd(final @NonNull Call call, final @Nullable Handshake handshake) {
        logWithTime("secureConnectEnd: " + handshake);
    }

    @Override
    public void connectEnd(final @NonNull Call call,
                           final @NonNull InetSocketAddress inetSocketAddress,
                           final @Nullable Proxy proxy,
                           final @Nullable Protocol protocol) {
        logWithTime("connectEnd: " + protocol);
    }

    @Override
    public void connectFailed(final @NonNull Call call,
                              final @NonNull InetSocketAddress inetSocketAddress,
                              final @Nullable Proxy proxy,
                              final @Nullable Protocol protocol,
                              final @NonNull JayoException je) {
        logWithTime("connectFailed: " + protocol + " " + je);
    }

    @Override
    public void connectionAcquired(final @NonNull Call call, final @NonNull Connection connection) {
        logWithTime("connectionAcquired: " + connection);
    }

    @Override
    public void connectionReleased(final @NonNull Call call, final @NonNull Connection connection) {
        logWithTime("connectionReleased");
    }

    @Override
    public void requestHeadersStart(final @NonNull Call call) {
        logWithTime("requestHeadersStart");
    }

    @Override
    public void requestHeadersEnd(final @NonNull Call call, final @NonNull ClientRequest request) {
        logWithTime("requestHeadersEnd");
    }

    @Override
    public void requestBodyStart(final @NonNull Call call) {
        logWithTime("requestBodyStart");
    }

    @Override
    public void requestBodyEnd(final @NonNull Call call, final long byteCount) {
        logWithTime("requestBodyEnd: byteCount=" + byteCount);
    }

    @Override
    public void requestFailed(final @NonNull Call call, final @NonNull JayoException je) {
        logWithTime("requestFailed: " + je);
    }

    @Override
    public void responseHeadersStart(final @NonNull Call call) {
        logWithTime("responseHeadersStart");
    }

    @Override
    public void responseHeadersEnd(final @NonNull Call call, final @NonNull ClientResponse response) {
        logWithTime("responseHeadersEnd: " + response);
    }

    @Override
    public void responseBodyStart(final @NonNull Call call) {
        logWithTime("responseBodyStart");
    }

    @Override
    public void responseBodyEnd(final @NonNull Call call, final long byteCount) {
        logWithTime("responseBodyEnd: byteCount=" + byteCount);
    }

    @Override
    public void responseFailed(final @NonNull Call call, final @NonNull JayoException je) {
        logWithTime("responseFailed: " + je);
    }

    @Override
    public void callEnd(final @NonNull Call call) {
        logWithTime("callEnd");
    }

    @Override
    public void callFailed(final @NonNull Call call, final @NonNull JayoException je) {
        logWithTime("callFailed: " + je);
    }

    @Override
    public void canceled(final @NonNull Call call) {
        logWithTime("canceled");
    }

    @Override
    public void satisfactionFailure(final @NonNull Call call, final @NonNull ClientResponse response) {
        logWithTime("satisfactionFailure: " + response);
    }

    @Override
    public void cacheHit(final @NonNull Call call, final @NonNull ClientResponse response) {
        logWithTime("cacheHit: " + response);
    }

    @Override
    public void cacheMiss(final @NonNull Call call) {
        logWithTime("cacheMiss");
    }

    @Override
    public void cacheConditionalHit(final @NonNull Call call, final @NonNull ClientResponse cachedResponse) {
        logWithTime("cacheConditionalHit: " + cachedResponse);
    }

    private void logWithTime(String message) {
        final var timeMs = Duration.ofNanos(System.nanoTime() - startNs).toMillis();
        logger.log("[" + timeMs + " ms] " + message);
    }
}
