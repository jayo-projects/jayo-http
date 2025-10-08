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

package jayo.http.internal.connection

import jayo.JayoException
import jayo.http.*
import jayo.network.Proxy
import jayo.tls.Handshake
import jayo.tls.Protocol
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

class ClientRuleEventListener(
    var logger: (String) -> Unit,
) : EventListener(),
    EventListener.Factory {
    private var startNs: Long? = null

    override fun create(call: Call): EventListener = this

    override fun callStart(call: Call) {
        startNs = System.nanoTime()

        logWithTime("callStart: ${call.request()}")
    }

    override fun dispatcherQueueStart(
        call: Call,
        dispatcher: Dispatcher,
    ) {
        logWithTime("dispatcherQueueStart: queuedCallsCount=${(dispatcher as RealDispatcher).queuedCallsCount()}")
    }

    override fun dispatcherQueueEnd(
        call: Call,
        dispatcher: Dispatcher,
    ) {
        logWithTime("dispatcherQueueEnd: queuedCallsCount=${(dispatcher as RealDispatcher).queuedCallsCount()}")
    }

    override fun proxySelected(
        call: Call,
        url: HttpUrl,
        proxy: Proxy?,
    ) {
        logWithTime("proxySelected: $url $proxy")
    }

    override fun dnsStart(
        call: Call,
        domainName: String,
    ) {
        logWithTime("dnsStart: $domainName")
    }

    override fun dnsEnd(
        call: Call,
        domainName: String,
        inetAddressList: List<InetAddress>,
    ) {
        logWithTime("dnsEnd: $inetAddressList")
    }

    override fun connectStart(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy?,
    ) {
        logWithTime("connectStart: $inetSocketAddress $proxy")
    }

    override fun secureConnectStart(call: Call) {
        logWithTime("secureConnectStart")
    }

    override fun secureConnectEnd(
        call: Call,
        handshake: Handshake?,
    ) {
        logWithTime("secureConnectEnd: $handshake")
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy?,
        protocol: Protocol?,
    ) {
        logWithTime("connectEnd: $protocol")
    }

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy?,
        protocol: Protocol?,
        je: JayoException,
    ) {
        logWithTime("connectFailed: $protocol $je")
    }

    override fun connectionAcquired(
        call: Call,
        connection: Connection,
    ) {
        logWithTime("connectionAcquired: $connection")
    }

    override fun connectionReleased(
        call: Call,
        connection: Connection,
    ) {
        logWithTime("connectionReleased")
    }

    override fun requestHeadersStart(call: Call) {
        logWithTime("requestHeadersStart")
    }

    override fun requestHeadersEnd(
        call: Call,
        request: ClientRequest,
    ) {
        logWithTime("requestHeadersEnd")
    }

    override fun requestBodyStart(call: Call) {
        logWithTime("requestBodyStart")
    }

    override fun requestBodyEnd(
        call: Call,
        byteCount: Long,
    ) {
        logWithTime("requestBodyEnd: byteCount=$byteCount")
    }

    override fun requestFailed(
        call: Call,
        je: JayoException,
    ) {
        logWithTime("requestFailed: $je")
    }

    override fun responseHeadersStart(call: Call) {
        logWithTime("responseHeadersStart")
    }

    override fun responseHeadersEnd(
        call: Call,
        response: ClientResponse,
    ) {
        logWithTime("responseHeadersEnd: $response")
    }

    override fun responseBodyStart(call: Call) {
        logWithTime("responseBodyStart")
    }

    override fun responseBodyEnd(
        call: Call,
        byteCount: Long,
    ) {
        logWithTime("responseBodyEnd: byteCount=$byteCount")
    }

    override fun responseFailed(
        call: Call,
        je: JayoException,
    ) {
        logWithTime("responseFailed: $je")
    }

    override fun callEnd(call: Call) {
        logWithTime("callEnd")
    }

    override fun callFailed(
        call: Call,
        je: JayoException,
    ) {
        logWithTime("callFailed: $je")
    }

    override fun canceled(call: Call) {
        logWithTime("canceled")
    }

    override fun satisfactionFailure(
        call: Call,
        response: ClientResponse,
    ) {
        logWithTime("satisfactionFailure")
    }

    override fun cacheMiss(call: Call) {
        logWithTime("cacheMiss")
    }

    override fun cacheHit(
        call: Call,
        response: ClientResponse,
    ) {
        logWithTime("cacheHit")
    }

    override fun cacheConditionalHit(
        call: Call,
        cachedResponse: ClientResponse,
    ) {
        logWithTime("cacheConditionalHit")
    }

    override fun retryDecision(
        call: Call,
        je: JayoException,
        retry: Boolean,
    ) {
        logWithTime("retryDecision")
    }

    override fun followUpDecision(
        call: Call,
        networkResponse: ClientResponse,
        nextRequest: ClientRequest?,
    ) {
        logWithTime("followUpDecision")
    }

    private fun logWithTime(message: String) {
        val startNs = startNs
        val timeMs =
            if (startNs == null) {
                // Event occurred before start, for an example an early cancel.
                0L
            } else {
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
            }

        logger.invoke("[$timeMs ms] $message")
    }
}
