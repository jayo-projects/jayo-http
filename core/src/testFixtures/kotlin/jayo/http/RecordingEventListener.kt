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
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.offset
import org.junit.jupiter.api.Assertions.fail
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit

open class RecordingEventListener(
    /**
     * An override to ignore the normal order that is enforced.
     * EventListeners added by Interceptors will not see all events.
     */
    private val enforceOrder: Boolean = true,
) : EventListener() {
    val eventSequence: Deque<CallEvent> = ConcurrentLinkedDeque()

    private val forbiddenLocks = mutableListOf<Any>()

    /** The timestamp of the last-taken event, used to measure elapsed time between events. */
    private var lastTimestampNs: Long? = null

    /** Confirm that the thread does not hold a lock on `lock` during the callback. */
    fun forbidLock(lock: Any) {
        forbiddenLocks.add(lock)
    }

    /**
     * Removes recorded events up to (and including) an event is found whose class equals [eventClass] and returns it.
     */
    fun <T : CallEvent> removeUpToEvent(eventClass: Class<T>): T {
        val fullEventSequence = eventSequence.toList()
        try {
            while (true) {
                val event = takeEvent()
                if (eventClass.isInstance(event)) {
                    return eventClass.cast(event)
                }
            }
        } catch (e: NoSuchElementException) {
            throw kotlin.AssertionError("full event sequence: $fullEventSequence", e)
        }
    }

    inline fun <reified T : CallEvent> removeUpToEvent(): T = removeUpToEvent(T::class.java)

    inline fun <reified T : CallEvent> findEvent(): T = eventSequence.first { it is T } as T

    /**
     * Remove and return the next event from the recorded sequence.
     *
     * @param eventClass a class to assert that the returned event is an instance of, or null to take any event class.
     * @param elapsedMs the time in milliseconds elapsed since the immediately preceding event, or -1L to take any
     * duration.
     */
    fun takeEvent(
        eventClass: Class<out CallEvent>? = null,
        elapsedMs: Long = -1L,
    ): CallEvent {
        val result = eventSequence.remove()
        val actualElapsedNs = result.timestampNs - (lastTimestampNs ?: result.timestampNs)
        lastTimestampNs = result.timestampNs

        if (eventClass != null) {
            assertThat(result).isInstanceOf(eventClass)
        }

        if (elapsedMs != -1L) {
            assertThat(
                TimeUnit.NANOSECONDS
                    .toMillis(actualElapsedNs)
                    .toDouble(),
            ).isCloseTo(elapsedMs.toDouble(), offset(100.0))
        }

        return result
    }

    fun recordedEventTypes() = eventSequence.map { it.name }

    fun clearAllEvents() {
        while (eventSequence.isNotEmpty()) {
            takeEvent()
        }
    }

    private fun logEvent(e: CallEvent) {
        for (lock in forbiddenLocks) {
            assertThat(Thread.holdsLock(lock)).isFalse()
        }

        if (enforceOrder) {
            checkForStartEvent(e)
        }

        eventSequence.offer(e)
    }

    private fun checkForStartEvent(e: CallEvent) {
        if (eventSequence.isEmpty()) {
            assertThat(e).matches { it is CallStart || it is Canceled }
        } else {
            eventSequence.forEach loop@{
                when (e.closes(it)) {
                    null -> return // no open event
                    true -> return // found open event
                    false -> return@loop // this is not the open event so continue
                }
            }
            fail<Any>("event $e without matching start event")
        }
    }

    override fun proxySelected(
        call: Call,
        url: HttpUrl,
        proxy: Proxy?,
    ) = logEvent(ProxySelected(System.nanoTime(), call, url, proxy))

    override fun dnsStart(
        call: Call,
        domainName: String,
    ) = logEvent(DnsStart(System.nanoTime(), call, domainName))

    override fun dnsEnd(
        call: Call,
        domainName: String,
        inetAddressList: List<InetAddress>,
    ) = logEvent(DnsEnd(System.nanoTime(), call, domainName, inetAddressList))

    override fun connectStart(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy?,
    ) = logEvent(ConnectStart(System.nanoTime(), call, inetSocketAddress, proxy))

    override fun secureConnectStart(call: Call) = logEvent(SecureConnectStart(System.nanoTime(), call))

    override fun secureConnectEnd(
        call: Call,
        handshake: Handshake?,
    ) = logEvent(SecureConnectEnd(System.nanoTime(), call, handshake))

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy?,
        protocol: Protocol?,
    ) = logEvent(ConnectEnd(System.nanoTime(), call, inetSocketAddress, proxy, protocol))

    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy?,
        protocol: Protocol?,
        je: JayoException,
    ) = logEvent(ConnectFailed(System.nanoTime(), call, inetSocketAddress, proxy, protocol, je))

    override fun connectionAcquired(
        call: Call,
        connection: Connection,
    ) = logEvent(ConnectionAcquired(System.nanoTime(), call, connection))

    override fun connectionReleased(
        call: Call,
        connection: Connection,
    ) = logEvent(ConnectionReleased(System.nanoTime(), call, connection))

    override fun callStart(call: Call) = logEvent(CallStart(System.nanoTime(), call))

    override fun requestHeadersStart(call: Call) = logEvent(RequestHeadersStart(System.nanoTime(), call))

    override fun requestHeadersEnd(
        call: Call,
        request: ClientRequest,
    ) = logEvent(RequestHeadersEnd(System.nanoTime(), call, request.headers.byteCount()))

    override fun requestBodyStart(call: Call) = logEvent(RequestBodyStart(System.nanoTime(), call))

    override fun requestBodyEnd(
        call: Call,
        byteCount: Long,
    ) = logEvent(RequestBodyEnd(System.nanoTime(), call, byteCount))

    override fun requestFailed(
        call: Call,
        je: JayoException,
    ) = logEvent(RequestFailed(System.nanoTime(), call, je))

    override fun responseHeadersStart(call: Call) = logEvent(ResponseHeadersStart(System.nanoTime(), call))

    override fun responseHeadersEnd(
        call: Call,
        response: ClientResponse,
    ) = logEvent(ResponseHeadersEnd(System.nanoTime(), call, response.headers.byteCount()))

    override fun responseBodyStart(call: Call) = logEvent(ResponseBodyStart(System.nanoTime(), call))

    override fun responseBodyEnd(
        call: Call,
        byteCount: Long,
    ) = logEvent(ResponseBodyEnd(System.nanoTime(), call, byteCount))

    override fun responseFailed(
        call: Call,
        je: JayoException,
    ) = logEvent(ResponseFailed(System.nanoTime(), call, je))

    override fun callEnd(call: Call) = logEvent(CallEnd(System.nanoTime(), call))

    override fun callFailed(
        call: Call,
        je: JayoException,
    ) = logEvent(CallFailed(System.nanoTime(), call, je))

    override fun canceled(call: Call) = logEvent(Canceled(System.nanoTime(), call))

    override fun satisfactionFailure(
        call: Call,
        response: ClientResponse,
    ) = logEvent(SatisfactionFailure(System.nanoTime(), call))

    override fun cacheMiss(call: Call) = logEvent(CacheMiss(System.nanoTime(), call))

    override fun cacheHit(
        call: Call,
        response: ClientResponse,
    ) = logEvent(CacheHit(System.nanoTime(), call))

    override fun cacheConditionalHit(
        call: Call,
        cachedResponse: ClientResponse,
    ) = logEvent(CacheConditionalHit(System.nanoTime(), call))

    override fun retryDecision(
        call: Call,
        exception: JayoException,
        retry: Boolean,
    ) = logEvent(RetryDecision(System.nanoTime(), call, exception, retry))

    override fun followUpDecision(
        call: Call,
        networkResponse: ClientResponse,
        nextRequest: ClientRequest?,
    ) = logEvent(FollowUpDecision(System.nanoTime(), call, networkResponse, nextRequest))
}
