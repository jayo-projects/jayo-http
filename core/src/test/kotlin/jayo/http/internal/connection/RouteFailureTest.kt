/*
 * Copyright (c) 2025-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 *
 * Forked from OkHttp (https://github.com/square/okhttp), original copyright is below
 *
 * Copyright (C) 2013 Square, Inc.
 * Copyright (C) 2011 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jayo.http.internal.connection

import jayo.JayoException
import jayo.JayoTimeoutException
import jayo.http.*
import jayo.http.internal.RecordedResponse
import jayo.http.internal.SpecificHostSocketAddressFactory
import jayo.http.internal.toOkhttp
import jayo.network.Proxy
import jayo.tls.ClientTlsSocket
import jayo.tls.Protocol
import jayo.tools.JayoTlsUtils
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import mockwebserver3.junit5.StartStop
import okhttp3.internal.http2.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration

class RouteFailureTest {
    private lateinit var socketFactory: SpecificHostSocketAddressFactory
    private lateinit var client: RealJayoHttpClient

    @RegisterExtension
    val clientTestRule = JayoHttpClientTestRule()

    @StartStop
    val server1 = MockWebServer()

    @StartStop
    val server2 = MockWebServer()

    private var eventRecorder = EventRecorder()

    val dns = FakeDns()

    val ipv4 = InetAddress.getByName("203.0.113.1")
    val ipv6 = InetAddress.getByName("2001:db8:ffff:ffff:ffff:ffff:ffff:1")

    val refusedStream =
        MockResponse
            .Builder()
            .onRequestStart(SocketEffect.CloseStream(ErrorCode.REFUSED_STREAM.httpCode))
            .build()
    val bodyResponse = MockResponse(body = "body")

    @BeforeEach
    fun setUp() {
        socketFactory = SpecificHostSocketAddressFactory(InetSocketAddress(server1.hostName, server1.port))

        client =
            (clientTestRule
                .newClientBuilder()
                .connectTimeout(Duration.ofMillis(250))
                .readTimeout(Duration.ofMillis(250))
                .writeTimeout(Duration.ofMillis(250))
                .eventListenerFactory(clientTestRule.wrap(eventRecorder))
                .dns(dns) as RealJayoHttpClient.Builder)
                .apply {
                    networkSocketBuilder.onConnect { requested ->
                        socketFactory.computeAddress(requested)
                    }
                }
                .build() as RealJayoHttpClient
    }

    @RepeatedTest(10)
    fun http2OneBadHostOneGoodNoRetryOnConnectionFailure() {
        enableProtocol(Protocol.HTTP_2)

        val request = ClientRequest.get(server1.url("/").toJayo())

        server1.enqueue(refusedStream)
        server2.enqueue(bodyResponse)

        dns[server1.hostName] = listOf(ipv6, ipv4)
        socketFactory[ipv6] = server1.socketAddress
        socketFactory[ipv4] = server2.socketAddress

        client =
            client
                .newBuilder()
                .fastFallback(false)
                .retryOnConnectionFailure(false)
                .build() as RealJayoHttpClient

        executeSynchronously(request)
            .assertFailureMatches("stream was reset: REFUSED_STREAM")

        assertThat(client.routeDatabase.failedRoutes).isEmpty()
        server1.takeRequest()
        assertThat(server1.requestCount).isEqualTo(1)
        assertThat(server2.requestCount).isEqualTo(0)

//        assertThat(clientTestRule.recordedConnectionEventTypes()).containsExactly(
//            "ConnectStart",
//            "ConnectEnd",
//            "ConnectionAcquired",
//            "ConnectionReleased",
//        )
    }

    @Test
    fun http2OneBadHostOneGoodRetryOnConnectionFailure() {
        enableProtocol(Protocol.HTTP_2)

        val request = ClientRequest.get(server1.url("/").toJayo())

        server1.enqueue(refusedStream)
        server1.enqueue(refusedStream)
        server2.enqueue(bodyResponse)

        dns[server1.hostName] = listOf(ipv6, ipv4)
        socketFactory[ipv6] = server1.socketAddress
        socketFactory[ipv4] = server2.socketAddress

        client =
            client
                .newBuilder()
                .fastFallback(false)
                .retryOnConnectionFailure(true)
                .build() as RealJayoHttpClient

        executeSynchronously(request)
            .assertBody("body")

        assertThat(client.routeDatabase.failedRoutes).isEmpty()
        // TODO check if we expect a second request to server1, before attempting server2
        assertThat(server1.requestCount).isEqualTo(2)
        assertThat(server2.requestCount).isEqualTo(1)

//        assertThat(clientTestRule.recordedConnectionEventTypes()).containsExactly(
//            "ConnectStart",
//            "ConnectEnd",
//            "ConnectionAcquired",
//            "NoNewExchanges",
//            "ConnectionReleased",
//            "ConnectionClosed",
//            "ConnectStart",
//            "ConnectEnd",
//            "ConnectionAcquired",
//            "ConnectionReleased",
//        )
    }

    @RepeatedTest(10)
    fun http2OneBadHostOneGoodNoRetryOnConnectionFailureFastFallback() {
        enableProtocol(Protocol.HTTP_2)

        val request = ClientRequest.get(server1.url("/").toJayo())

        server1.enqueue(refusedStream)
        server2.enqueue(bodyResponse)

        dns[server1.hostName] = listOf(ipv6, ipv4)
        socketFactory[ipv6] = server1.socketAddress
        socketFactory[ipv4] = server2.socketAddress

        client =
            client
                .newBuilder()
                .fastFallback(true)
                .retryOnConnectionFailure(false)
                .build() as RealJayoHttpClient

        executeSynchronously(request)
            .assertFailureMatches("stream was reset: REFUSED_STREAM")

        assertThat(client.routeDatabase.failedRoutes).isEmpty()
        server1.takeRequest()
        assertThat(server1.requestCount).isEqualTo(1)
        assertThat(server2.requestCount).isEqualTo(0)

//        assertThat(clientTestRule.recordedConnectionEventTypes()).containsExactly(
//            "ConnectStart",
//            "ConnectEnd",
//            "ConnectionAcquired",
//            "ConnectionReleased",
//        )
    }

    @Tag("no-ci")
    @Test
    fun http2OneBadHostOneGoodRetryOnConnectionFailureFastFallback() {
        enableProtocol(Protocol.HTTP_2)

        val request = ClientRequest.get(server1.url("/").toJayo())

        server1.enqueue(refusedStream)
        server1.enqueue(refusedStream)
        server2.enqueue(bodyResponse)

        dns[server1.hostName] = listOf(ipv6, ipv4)
        socketFactory[ipv6] = server1.socketAddress
        socketFactory[ipv4] = server2.socketAddress

        client =
            client
                .newBuilder()
                .fastFallback(true)
                .retryOnConnectionFailure(true)
                .build() as RealJayoHttpClient

        executeSynchronously(request)
            .assertBody("body")

        assertThat(client.routeDatabase.failedRoutes).isEmpty()
        // TODO check if we expect a second request to server1, before attempting server2
        assertThat(server1.requestCount).isEqualTo(2)
        assertThat(server2.requestCount).isEqualTo(1)

//        assertThat(clientTestRule.recordedConnectionEventTypes()).containsExactly(
//            "ConnectStart",
//            "ConnectEnd",
//            "ConnectionAcquired",
//            "NoNewExchanges",
//            "ConnectionReleased",
//            "ConnectionClosed",
//            "ConnectStart",
//            "ConnectEnd",
//            "ConnectionAcquired",
//            "ConnectionReleased",
//        )
    }

    @RepeatedTest(10)
    fun http2OneBadHostRetryOnConnectionFailure() {
        enableProtocol(Protocol.HTTP_2)

        val request = ClientRequest.get(server1.url("/").toJayo())

        server1.enqueue(refusedStream)
        server1.enqueue(refusedStream)

        dns[server1.hostName] = listOf(ipv6)
        socketFactory[ipv6] = server1.socketAddress

        client =
            client
                .newBuilder()
                .fastFallback(false)
                .retryOnConnectionFailure(true)
                .build() as RealJayoHttpClient

        executeSynchronously(request)
            .assertFailureMatches("stream was reset: REFUSED_STREAM")

        assertThat(client.routeDatabase.failedRoutes).isEmpty()
        server1.takeRequest()
        assertThat(server1.requestCount).isEqualTo(1)

//        assertThat(clientTestRule.recordedConnectionEventTypes()).containsExactly(
//            "ConnectStart",
//            "ConnectEnd",
//            "ConnectionAcquired",
//            "ConnectionReleased",
//        )
    }

    @RepeatedTest(10)
    fun http2OneBadHostRetryOnConnectionFailureFastFallback() {
        enableProtocol(Protocol.HTTP_2)

        val request = ClientRequest.get(server1.url("/").toJayo())

        server1.enqueue(refusedStream)
        server1.enqueue(refusedStream)

        dns[server1.hostName] = listOf(ipv6)
        socketFactory[ipv6] = server1.socketAddress

        client =
            client
                .newBuilder()
                .fastFallback(true)
                .retryOnConnectionFailure(true)
                .build() as RealJayoHttpClient

        executeSynchronously(request)
            .assertFailureMatches("stream was reset: REFUSED_STREAM")

        assertThat(client.routeDatabase.failedRoutes).isEmpty()
        server1.takeRequest()
        assertThat(server1.requestCount).isEqualTo(1)

//        assertThat(clientTestRule.recordedConnectionEventTypes()).containsExactly(
//            "ConnectStart",
//            "ConnectEnd",
//            "ConnectionAcquired",
//            "ConnectionReleased",
//        )
    }

    @Test
    fun proxyMoveTestCleanClose() {
        proxyMoveTest(true)
    }

    @Test
    fun proxyMoveTestNoCleanClose() {
        proxyMoveTest(false)
    }

    private fun proxyMoveTest(cleanClose: Boolean) {
        // Define a single Proxy at myproxy:8008 that will artificially move during the test
        val socketAddress = InetSocketAddress.createUnresolved("myproxy", 8008)
        val proxies = Proxies.of(Proxy.http(socketAddress))

        // Define two host names for the DNS routing of fake proxy servers
        val proxyServer1 = InetAddress.getByAddress("proxyServer1", byteArrayOf(127, 0, 0, 2))
        val proxyServer2 = InetAddress.getByAddress("proxyServer2", byteArrayOf(127, 0, 0, 3))

        println("Proxy Server 1 is ${server1.socketAddress}")
        println("Proxy Server 2 is ${server2.socketAddress}")

        // Since myproxy:8008 won't resolve, redirect with DNS to proxyServer1
        // Then redirect socket connection to server1
        dns["myproxy"] = listOf(proxyServer1)
        socketFactory[proxyServer1] = server1.socketAddress

        client = client.newBuilder().proxies(proxies).build() as RealJayoHttpClient

        val request = ClientRequest.get(server1.url("/").toJayo())

        server1.enqueue(MockResponse(200))
        server2.enqueue(MockResponse(200))
        server2.enqueue(MockResponse(200))

        println("\n\nRequest to ${server1.socketAddress}")
        executeSynchronously(request)
            .assertSuccessful()
            .assertCode(200)

        println("server1.requestCount ${server1.requestCount}")
        assertThat(server1.requestCount).isEqualTo(1)

        // Close the proxy server
        if (cleanClose) {
            server1.close()
        }

        // Now redirect with DNS to proxyServer2
        // Then redirect socket connection to server2
        dns["myproxy"] = listOf(proxyServer2)
        socketFactory[proxyServer2] = server2.socketAddress

        println("\n\nRequest to ${server2.socketAddress}")
        executeSynchronously(request)
            .apply {
                // We may have a single failed request if not clean shutdown
                if (cleanClose) {
                    assertSuccessful()
                    assertCode(200)

                    assertThat(server2.requestCount).isEqualTo(1)
                } else {
                    this.assertFailure(JayoTimeoutException::class.java)
                }
            }

        println("\n\nRequest to ${server2.socketAddress}")
        executeSynchronously(request)
            .assertSuccessful()
            .assertCode(200)
    }

    private fun enableProtocol(protocol: Protocol) {
        enableTls()
        client =
            client
                .newBuilder()
                .protocols(listOf(protocol, Protocol.HTTP_1_1))
                .build() as RealJayoHttpClient
        server1.protocols = client.protocols.toOkhttp()
        server2.protocols = client.protocols.toOkhttp()
    }

    private fun enableTls() {
        val handshakeCertificates = JayoTlsUtils.localhost()

        client =
            client
                .newBuilder()
                .tlsConfig(ClientTlsSocket.builder(handshakeCertificates))
                .hostnameVerifier(RecordingHostnameVerifier())
                .build() as RealJayoHttpClient
        server1.useHttps(handshakeCertificates.sslSocketFactory())
        server2.useHttps(handshakeCertificates.sslSocketFactory())
    }

    private fun executeSynchronously(request: ClientRequest): RecordedResponse {
        val call = client.newCall(request)
        return try {
            val response = call.execute()
            val bodyString = response.body.string()
            RecordedResponse(request, response, bodyString, null)
        } catch (je: JayoException) {
            RecordedResponse(request, null, null, je)
        }
    }
}