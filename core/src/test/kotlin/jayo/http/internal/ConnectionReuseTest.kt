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

import jayo.JayoException
import jayo.http.*
import jayo.http.internal.connection.RealConnectionPool
import jayo.tls.ClientHandshakeCertificates
import jayo.tls.ClientTlsSocket
import jayo.tls.JssePlatformRule
import jayo.tls.Protocol
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect.CloseSocket
import mockwebserver3.SocketEffect.ShutdownConnection
import mockwebserver3.junit5.StartStop
import okhttp3.Headers.Companion.headersOf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.test.assertFailsWith

@Timeout(30)
class ConnectionReuseTest {
    @RegisterExtension
    val platform = JssePlatformRule()

    @RegisterExtension
    val clientTestRule = JayoHttpClientTestRule()

    @StartStop
    private val server = MockWebServer()

    private var client: JayoHttpClient = clientTestRule.newClient()

    @Test
    fun connectionsAreReused() {
        platform.assumeBouncyCastle()

        server.enqueue(MockResponse(body = "a"))
        server.enqueue(MockResponse(body = "b"))
        val request = ClientRequest.get(server.url("/").toJayo())
        assertConnectionReused(request, request)
    }

    @Test
    fun connectionsAreReusedForPosts() {
        platform.assumeBouncyCastle()

        server.enqueue(MockResponse(body = "a"))
        server.enqueue(MockResponse(body = "b"))
        val request = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .post(ClientRequestBody.create("request body", MediaType.get("text/plain")))
        assertConnectionReused(request, request)
    }

    @Test
    fun connectionsAreReusedWithHttp2() {
        enableHttp2()
        server.enqueue(MockResponse(body = "a"))
        server.enqueue(MockResponse(body = "b"))
        val request = ClientRequest.get(server.url("/").toJayo())
        assertConnectionReused(request, request)
    }

    @Test
    fun connectionsAreNotReusedWithRequestConnectionClose() {
        platform.assumeBouncyCastle()

        server.enqueue(MockResponse(body = "a"))
        server.enqueue(MockResponse(body = "b"))
        val requestA = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .header("Connection", "close")
            .get()
        val requestB = ClientRequest.get(server.url("/").toJayo())
        assertConnectionNotReused(requestA, requestB)
    }

    @Test
    fun connectionsAreNotReusedWithResponseConnectionClose() {
        platform.assumeBouncyCastle()

        server.enqueue(
            MockResponse(
                headers = headersOf("Connection", "close"),
                body = "a",
            ),
        )
        server.enqueue(MockResponse(body = "b"))
        val requestA = ClientRequest.get(server.url("/").toJayo())
        val requestB = ClientRequest.get(server.url("/").toJayo())
        assertConnectionNotReused(requestA, requestB)
    }

    @Test
    fun connectionsAreNotReusedWithUnknownLengthResponseBody() {
        platform.assumeBouncyCastle()

        server.enqueue(
            MockResponse
                .Builder()
                .body("a")
                .clearHeaders()
                .onResponseEnd(ShutdownConnection)
                .build(),
        )
        server.enqueue(MockResponse(body = "b"))
        val request = ClientRequest.get(server.url("/").toJayo())
        assertConnectionNotReused(request, request)
    }

    @Test
    fun connectionsAreNotReusedIfPoolIsSizeZero() {
        client =
            client
                .newBuilder()
                .connectionPool(RealConnectionPool(0, Duration.ofSeconds(5)))
                .build()
        server.enqueue(MockResponse(body = "a"))
        server.enqueue(MockResponse(body = "b"))
        val request = ClientRequest.get(server.url("/").toJayo())
        assertConnectionNotReused(request, request)
    }

    @Test
    fun connectionsReusedWithRedirectEvenIfPoolIsSizeZero() {
        client =
            client
                .newBuilder()
                .connectionPool(RealConnectionPool(0, Duration.ofSeconds(5)))
                .build()
        server.enqueue(
            MockResponse(
                code = 301,
                headers = headersOf("Location", "/b"),
                body = "a",
            ),
        )
        server.enqueue(MockResponse(body = "b"))
        val request = ClientRequest.get(server.url("/").toJayo())
        val response = client.newCall(request).execute()
        assertThat(response.body.string()).isEqualTo("b")
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)
    }

    @Test
    fun connectionsNotReusedWithRedirectIfDiscardingResponseIsSlow() {
        client =
            client
                .newBuilder()
                .connectionPool(RealConnectionPool(0, Duration.ofSeconds(5)))
                .build()
        server.enqueue(
            MockResponse
                .Builder()
                .code(301)
                .addHeader("Location: /b")
                .bodyDelay(1, TimeUnit.SECONDS)
                .body("a")
                .build(),
        )
        server.enqueue(MockResponse(body = "b"))
        val request = ClientRequest.get(server.url("/").toJayo())
        val response = client.newCall(request).execute()
        assertThat(response.body.string()).isEqualTo("b")
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
    }

    @Test
    fun silentRetryWhenIdempotentRequestFailsOnReusedConnection() {
        platform.assumeBouncyCastle()

        server.enqueue(MockResponse(body = "a"))
        server.enqueue(MockResponse.Builder().onResponseStart(CloseSocket()).build())
        server.enqueue(MockResponse(body = "b"))
        val request = ClientRequest.get(server.url("/").toJayo())
        val responseA = client.newCall(request).execute()
        assertThat(responseA.body.string()).isEqualTo("a")
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
        val responseB = client.newCall(request).execute()
        assertThat(responseB.body.string()).isEqualTo("b")
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
    }

    @Test
    fun http2ConnectionsAreSharedBeforeResponseIsConsumed() {
        enableHttp2()
        server.enqueue(MockResponse(body = "a"))
        server.enqueue(MockResponse(body = "b"))
        val request = ClientRequest.get(server.url("/").toJayo())
        val response1 = client.newCall(request).execute()
        val response2 = client.newCall(request).execute()
        response1.body.string() // Discard the response body.
        response2.body.string() // Discard the response body.
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)
    }

    @Test
    fun connectionsAreEvicted() {
        server.enqueue(MockResponse(body = "a"))
        server.enqueue(MockResponse(body = "b"))
        client =
            client
                .newBuilder()
                .connectionPool(RealConnectionPool(5, Duration.ofMillis(250)))
                .build()
        val request = ClientRequest.get(server.url("/").toJayo())
        val response1 = client.newCall(request).execute()
        assertThat(response1.body.string()).isEqualTo("a")

        // Give the thread pool a chance to evict.
        Thread.sleep(500)
        val response2 = client.newCall(request).execute()
        assertThat(response2.body.string()).isEqualTo("b")
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
    }

    @Test
    fun connectionsAreNotReusedIfSslSocketFactoryChanges() {
        enableHttps()
        server.enqueue(MockResponse())
        server.enqueue(MockResponse())
        val request = ClientRequest.get(server.url("/").toJayo())
        val response = client.newCall(request).execute()
        response.body.close()

        // This client shares a connection pool but has a different SSL socket factory.
        val handshakeCertificates = ClientHandshakeCertificates.create()
        val anotherClient =
            client
                .newBuilder()
                .tlsConfig(ClientTlsSocket.builder(handshakeCertificates))
                .build()

        // This client fails to connect because the new SSL socket factory refuses.
        assertFailsWith<JayoException> {
            anotherClient.newCall(request).execute()
        }.also { expected ->
            when (expected.cause) {
                is SSLException/*, is TlsFatalAlert*/ -> {}
                else -> throw expected
            }
        }
    }

    @Test
    fun connectionsAreNotReusedIfHostnameVerifierChanges() {
        enableHttps()
        server.enqueue(MockResponse())
        server.enqueue(MockResponse())
        val request = ClientRequest.get(server.url("/").toJayo())
        val response1 = client.newCall(request).execute()
        response1.body.close()

        // This client shares a connection pool but has a different SSL socket factory.
        val anotherClient =
            client
                .newBuilder()
                .hostnameVerifier(RecordingHostnameVerifier())
                .build()
        val response2 = anotherClient.newCall(request).execute()
        response2.body.close()
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
    }

    /**
     * Regression test for an edge case where closing response body in the HTTP engine doesn't release
     * the corresponding stream allocation. This test keeps those response bodies alive and reads
     * them after the redirect has completed. This forces a connection to not be reused where it would
     * be otherwise.
     *
     *
     * This test leaks a response body by not closing it.
     *
     * https://github.com/square/okhttp/issues/2409
     */
    @Test
    fun connectionsAreNotReusedIfNetworkInterceptorInterferes() {
        val responsesNotClosed: MutableList<ClientResponse?> = kotlin.collections.ArrayList()
        client =
            client
                .newBuilder()
                // Since this test knowingly leaks a connection, avoid using the default shared connection
                // pool, which should remain clean for subsequent tests.
                .connectionPool(RealConnectionPool(5, Duration.ofMinutes(5)))
                .addNetworkInterceptor { chain: Interceptor.Chain? ->
                    val response =
                        chain!!.proceed(
                            chain.request(),
                        )
                    responsesNotClosed.add(response)
                    response
                        .newBuilder()
                        .body(ClientResponseBody.create("unrelated response body!"))
                        .build()
                }.build()
        server.enqueue(
            MockResponse(
                code = 301,
                headers = headersOf("Location", "/b"),
                body = "/a has moved!",
            ),
        )
        server.enqueue(
            MockResponse(body = "/b is here"),
        )
        val request = ClientRequest.get(server.url("/").toJayo())
        val call = client.newCall(request)
        call.execute().use { response ->
            assertThat(
                response.body.string(),
            ).isEqualTo("unrelated response body!")
        }
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
        // No connection reuse.
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
        for (response in responsesNotClosed) {
            response!!.closeQuietly()
        }
    }

    private fun enableHttps() {
        enableHttpsAndAlpn(Protocol.HTTP_1_1)
    }

    private fun enableHttp2() {
        enableHttpsAndAlpn(Protocol.HTTP_2, Protocol.HTTP_1_1)
    }

    private fun enableHttpsAndAlpn(vararg protocols: Protocol) {
        val handshakeCertificates = platform.localhostHandshakeCertificates()
        client =
            client
                .newBuilder()
                .tlsConfig(ClientTlsSocket.builder(handshakeCertificates))
                .hostnameVerifier(RecordingHostnameVerifier())
                .protocols(protocols.toList())
                .build()
        server.useHttps(handshakeCertificates.sslSocketFactory())
        server.protocols = client.protocols.toOkhttp()
    }

    private fun assertConnectionReused(vararg requests: ClientRequest?) {
        for (i in requests.indices) {
            val response = client.newCall(requests[i]!!).execute()
            response.body.string() // Discard the response body.
            assertThat(server.takeRequest().exchangeIndex).isEqualTo(i)
        }
    }

    private fun assertConnectionNotReused(vararg requests: ClientRequest?) {
        for (request in requests) {
            val response = client.newCall(request!!).execute()
            response.body.string() // Discard the response body.
            assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
        }
    }
}
