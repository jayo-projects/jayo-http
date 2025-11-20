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

package jayo.http.internal

import jayo.JayoTimeoutException
import jayo.http.*
import jayo.http.CallEvent.*
import jayo.network.JayoConnectException
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junitpioneer.jupiter.RetryingTest
import org.opentest4j.TestAbortedException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.time.Duration
import kotlin.test.assertFailsWith

/**
 * This test binds two different web servers (IPv4 and IPv6) to the same port but on different local IP addresses.
 * Requests made to `127.0.0.1` will reach the IPv4 server, and requests made to `::1` will reach the IPv6 server.
 *
 * By orchestrating two different servers with the same port but different IP addresses, we can test what Jayo HTTP does
 * when both are reachable or if only one is reachable.
 *
 * This test only runs on host machines that have both IPv4 and IPv6 addresses for localhost.
 */
@Timeout(30)
class FastFallbackTest {
    @RegisterExtension
    val clientTestRule = JayoHttpClientTestRule()

    // Don't use JUnit 5 test rules for these; otherwise we can't bind them to a single local IP.
    private lateinit var localhostIpv4: InetAddress
    private lateinit var localhostIpv6: InetAddress
    private lateinit var serverIpv4: MockWebServer
    private lateinit var serverIpv6: MockWebServer

    private val eventRecorder = EventRecorder()
    private lateinit var client: JayoHttpClient
    private lateinit var url: HttpUrl

    /**
     * This is mutable and order matters. By default, it contains [IPv4, IPv6]. Tests may manipulate
     * it to prefer IPv6.
     */
    private var dnsResults = listOf<InetAddress>()

    @BeforeEach
    internal fun setUp() {
        val inetAddresses = InetAddress.getAllByName("localhost")
        localhostIpv4 = inetAddresses.firstOrNull { it is Inet4Address }
            ?: throw TestAbortedException()
        localhostIpv6 = inetAddresses.firstOrNull { it is Inet6Address }
            ?: throw TestAbortedException()

        serverIpv4 = MockWebServer()
        serverIpv4.start(localhostIpv4, 0) // Pick any available port.

        serverIpv6 = MockWebServer()
        serverIpv6.start(localhostIpv6, serverIpv4.port) // Pick the same port as the IPv4 server.

        dnsResults =
            listOf(
                localhostIpv4,
                localhostIpv6,
            )

        client =
            clientTestRule
                .newClientBuilder()
                .eventListenerFactory(clientTestRule.wrap(eventRecorder))
                .networkConfig {
                    it.connectTimeout(Duration.ofSeconds(60)) // Deliberately exacerbate slow fallbacks.
                }
                .dns { dnsResults }
                .fastFallback(true)
                .build()
        url =
            serverIpv4
                .url("/")
                .newBuilder()
                .host("localhost")
                .build()
                .toJayo()
    }

    @AfterEach
    internal fun tearDown() {
        serverIpv4.close()
        serverIpv6.close()
    }

    @Test
    fun callIpv6FirstEvenWhenIpv4IpIsListedFirst() {
        dnsResults =
            listOf(
                localhostIpv4,
                localhostIpv6,
            )
        serverIpv4.enqueue(
            MockResponse(body = "unexpected call to IPv4"),
        )
        serverIpv6.enqueue(
            MockResponse(body = "hello from IPv6"),
        )

        val call = client.newCall(ClientRequest.get(url))
        val response = call.execute()
        assertThat(response.body.string()).isEqualTo("hello from IPv6")

        // In the process we made one successful connection attempt.
        assertThat(eventRecorder.recordedEventTypes().filter { it == ConnectStart::class }).hasSize(1)
        assertThat(eventRecorder.recordedEventTypes().filter { it == ConnectFailed::class }).hasSize(0)
    }

    @Test
    fun callIpv6WhenBothServersAreReachable() {
        // Flip DNS results to prefer IPv6.
        dnsResults =
            listOf(
                localhostIpv6,
                localhostIpv4,
            )
        serverIpv4.enqueue(
            MockResponse(body = "unexpected call to IPv4"),
        )
        serverIpv6.enqueue(
            MockResponse(body = "hello from IPv6"),
        )

        val call = client.newCall(ClientRequest.get(url))
        val response = call.execute()
        assertThat(response.body.string()).isEqualTo("hello from IPv6")

        // In the process we made one successful connection attempt.
        assertThat(eventRecorder.recordedEventTypes().filter { it == ConnectStart::class }).hasSize(1)
        assertThat(eventRecorder.recordedEventTypes().filter { it == ConnectFailed::class }).hasSize(0)
    }

    @Test
    fun reachesIpv4WhenIpv6IsDown() {
        serverIpv6.close()
        serverIpv4.enqueue(
            MockResponse(body = "hello from IPv4"),
        )

        val call = client.newCall(ClientRequest.get(url))
        val response = call.execute()
        assertThat(response.body.string()).isEqualTo("hello from IPv4")

        // In the process we made one successful connection attempt.
        assertThat(eventRecorder.recordedEventTypes().filter { it == ConnectStart::class }).hasSize(2)
        assertThat(eventRecorder.recordedEventTypes().filter { it == ConnectFailed::class }).hasSize(1)
        assertThat(eventRecorder.recordedEventTypes().filter { it == ConnectEnd::class }).hasSize(1)
    }

    @Test
    fun reachesIpv6WhenIpv4IsDown() {
        serverIpv4.close()
        serverIpv6.enqueue(
            MockResponse(body = "hello from IPv6"),
        )

        val call = client.newCall(ClientRequest.get(url))
        val response = call.execute()
        assertThat(response.body.string()).isEqualTo("hello from IPv6")

        // In the process we made two connection attempts including one failure.
        assertThat(eventRecorder.recordedEventTypes().filter { it == ConnectStart::class }).hasSize(1)
        assertThat(eventRecorder.recordedEventTypes().filter { it == ConnectEnd::class }).hasSize(1)
        assertThat(eventRecorder.recordedEventTypes().filter { it == ConnectFailed::class }).hasSize(0)
    }

    @Test
    fun failsWhenBothServersAreDown() {
        serverIpv4.close()
        serverIpv6.close()

        val call = client.newCall(ClientRequest.get(url))
        assertFailsWith<JayoConnectException> {
            call.execute()
        }

        // In the process we made two unsuccessful connection attempts.
        assertThat(eventRecorder.recordedEventTypes().filter { it == ConnectStart::class }).hasSize(2)
        assertThat(eventRecorder.recordedEventTypes().filter { it == ConnectFailed::class }).hasSize(2)
    }

    @RetryingTest(5)
    fun reachesIpv4AfterUnreachableIpv6Address() {
        dnsResults =
            listOf(
                TestUtils.UNREACHABLE_ADDRESS_IPV6.address,
                localhostIpv4,
            )
        serverIpv6.close()
        serverIpv4.enqueue(
            MockResponse(body = "hello from IPv4"),
        )

        val call = client.newCall(ClientRequest.get(url))
        val response = call.execute()
        assertThat(response.body.string()).isEqualTo("hello from IPv4")

        // In the process we made two connection attempts including one failure.
        assertThat(eventRecorder.recordedEventTypes().filter { it == ConnectStart::class }).hasSize(2)
        assertThat(eventRecorder.recordedEventTypes().filter { it == ConnectFailed::class }).hasSize(1)
    }

    @Test
    fun timesOutWithFastFallbackDisabled() {
        dnsResults =
            listOf(
                TestUtils.UNREACHABLE_ADDRESS_IPV4.address,
                localhostIpv6,
            )
        serverIpv4.close()
        serverIpv6.enqueue(
            MockResponse(body = "hello from IPv6"),
        )

        client =
            client
                .newBuilder()
                .fastFallback(false)
                .callTimeout(Duration.ofMillis(500))
                .build()
        val call = client.newCall(ClientRequest.get(url))
        assertThatThrownBy { call.execute() }
            .isInstanceOf(JayoTimeoutException::class.java)
    }
}
