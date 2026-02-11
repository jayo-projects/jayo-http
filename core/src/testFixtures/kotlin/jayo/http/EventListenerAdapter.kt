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

package jayo.http

import jayo.JayoException
import jayo.http.CallEvent.*
import jayo.network.Proxy
import jayo.tls.Handshake
import jayo.tls.Protocol
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * This accepts events as function calls on [EventListener], and publishes them as subtypes of
 * [CallEvent].
 */
class EventListenerAdapter : EventListener {
    var listeners = listOf<(CallEvent) -> Unit>()

    private fun onEvent(listener: CallEvent) {
        for (function in listeners) {
            function(listener)
        }
    }

    override fun dispatcherQueueStart(asyncCall: Call.AsyncCall, dispatcher: Dispatcher) =
        onEvent(DispatcherQueueStart(System.nanoTime(), asyncCall.call(), dispatcher))

    override fun dispatcherQueueEnd(asyncCall: Call.AsyncCall, dispatcher: Dispatcher) =
        onEvent(DispatcherQueueEnd(System.nanoTime(), asyncCall.call(), dispatcher))

    override fun dispatcherExecution(asyncCall: Call.AsyncCall, dispatcher: Dispatcher) =
        onEvent(DispatcherExecution(System.nanoTime(), asyncCall.call(), dispatcher))

    override fun proxySelected(
        call: Call,
        url: HttpUrl,
        proxy: Proxy?,
    ) = onEvent(ProxySelected(System.nanoTime(), call, url, proxy))

    override fun dnsStart(call: Call, domainName: String) =
        onEvent(DnsStart(System.nanoTime(), call, domainName))

    override fun dnsEnd(
        call: Call,
        domainName: String,
        inetAddressList: List<InetAddress>,
    ) = onEvent(DnsEnd(System.nanoTime(), call, domainName, inetAddressList))

    override fun connectStart(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy?,
    ) = onEvent(ConnectStart(System.nanoTime(), call, inetSocketAddress, proxy))

    override fun secureConnectStart(call: Call) =
        onEvent(SecureConnectStart(System.nanoTime(), call))

    override fun secureConnectEnd(call: Call, handshake: Handshake?) =
        onEvent(SecureConnectEnd(System.nanoTime(), call, handshake))

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy?,
        protocol: Protocol?,
    ) = onEvent(ConnectEnd(System.nanoTime(), call, inetSocketAddress, proxy, protocol))

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy?,
        protocol: Protocol?,
        je: JayoException,
    ) = onEvent(
        ConnectFailed(
            System.nanoTime(),
            call,
            inetSocketAddress,
            proxy,
            protocol,
            je,
        ),
    )

    override fun connectionAcquired(call: Call, connection: Connection) =
        onEvent(ConnectionAcquired(System.nanoTime(), call, connection))

    override fun connectionReleased(call: Call, connection: Connection) =
        onEvent(ConnectionReleased(System.nanoTime(), call, connection))

    override fun callStart(call: Call) = onEvent(CallStart(System.nanoTime(), call))

    override fun requestHeadersStart(call: Call) =
        onEvent(RequestHeadersStart(System.nanoTime(), call))

    override fun requestHeadersEnd(call: Call, request: ClientRequest) =
        onEvent(RequestHeadersEnd(System.nanoTime(), call, request.headers.byteCount()))

    override fun requestBodyStart(call: Call) = onEvent(RequestBodyStart(System.nanoTime(), call))

    override fun requestBodyEnd(call: Call, byteCount: Long) =
        onEvent(RequestBodyEnd(System.nanoTime(), call, byteCount))

    override fun requestFailed(call: Call, je: JayoException) =
        onEvent(RequestFailed(System.nanoTime(), call, je))

    override fun responseHeadersStart(call: Call) =
        onEvent(ResponseHeadersStart(System.nanoTime(), call))

    override fun responseHeadersEnd(call: Call, response: ClientResponse) =
        onEvent(ResponseHeadersEnd(System.nanoTime(), call, response.headers.byteCount()))

    override fun responseBodyStart(call: Call) =
        onEvent(ResponseBodyStart(System.nanoTime(), call))

    override fun responseBodyEnd(call: Call, byteCount: Long) =
        onEvent(ResponseBodyEnd(System.nanoTime(), call, byteCount))

    override fun responseFailed(call: Call, je: JayoException) =
        onEvent(ResponseFailed(System.nanoTime(), call, je))

    override fun callEnd(call: Call) = onEvent(CallEnd(System.nanoTime(), call))

    override fun callFailed(call: Call, je: JayoException) =
        onEvent(CallFailed(System.nanoTime(), call, je))

    override fun canceled(call: Call) = onEvent(Canceled(System.nanoTime(), call))

    override fun satisfactionFailure(call: Call, response: ClientResponse) =
        onEvent(SatisfactionFailure(System.nanoTime(), call))

    override fun cacheMiss(call: Call) = onEvent(CacheMiss(System.nanoTime(), call))

    override fun cacheHit(call: Call, response: ClientResponse) =
        onEvent(CacheHit(System.nanoTime(), call))

    override fun cacheConditionalHit(call: Call, cachedResponse: ClientResponse) =
        onEvent(CacheConditionalHit(System.nanoTime(), call))

    override fun retryDecision(
        call: Call,
        exception: JayoException,
        retry: Boolean,
    ) = onEvent(RetryDecision(System.nanoTime(), call, exception, retry))

    override fun followUpDecision(
        call: Call,
        networkResponse: ClientResponse,
        nextRequest: ClientRequest?,
    ) = onEvent(FollowUpDecision(System.nanoTime(), call, networkResponse, nextRequest))
}
