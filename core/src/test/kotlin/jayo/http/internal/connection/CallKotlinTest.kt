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
import jayo.Writer
import jayo.http.*
import jayo.http.internal.DoubleInetAddressDns
import jayo.http.internal.TestUtils
import jayo.http.internal.TestUtils.assertSuppressed
import jayo.http.internal.connection.RealConnection.IDLE_CONNECTION_HEALTHY_NS
import jayo.http.testing.Flaky
import jayo.network.Proxy
import jayo.tls.ClientTlsSocket
import jayo.tls.JssePlatformRule
import jayo.tools.JayoTlsUtils
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect.CloseSocket
import mockwebserver3.junit5.StartStop
import okhttp3.Headers.Companion.headersOf
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junitpioneer.jupiter.RetryingTest
import java.security.cert.X509Certificate
import java.time.Duration
import kotlin.test.assertFailsWith

@Timeout(30)
class CallKotlinTest {
    @JvmField
    @RegisterExtension
    val platform = JssePlatformRule()

    @JvmField
    @RegisterExtension
    val clientTestRule =
        JayoHttpClientTestRule().apply {
            recordFrames = true
            recordTlsDebug = true
        }

    private var client = clientTestRule.newClient()

    @StartStop
    private val server = MockWebServer()

    @Test
    fun legalToExecuteTwiceCloning() {
        server.enqueue(MockResponse(body = "abc"))
        server.enqueue(MockResponse(body = "def"))

        val request = ClientRequest.get(server.url("/").toJayo())

        val call = client.newCall(request)
        val response1 = call.execute()

        val cloned = call.clone()
        val response2 = cloned.execute()

        assertThat("abc").isEqualTo(response1.body.string())
        assertThat("def").isEqualTo(response2.body.string())
    }

    @Test
    @Flaky
    fun testMockWebserverRequest() {
        enableTls()

        server.enqueue(MockResponse(body = "abc"))

        val request = ClientRequest.builder().url(server.url("/").toJayo()).get()

        val response = client.newCall(request).execute()

        response.use {
            assertThat(response.statusCode).isEqualTo(200)
            assertThat((response.handshake!!.peerCertificates.single() as X509Certificate).subjectX500Principal.name)
                .isEqualTo("CN=localhost")
        }
    }

    private fun enableTls() {
        val handshakeCertificates = JayoTlsUtils.localhost()
        client =
            client
                .newBuilder()
                .tlsConfig(ClientTlsSocket.builder(handshakeCertificates))
                .build()
        server.useHttps(handshakeCertificates.sslSocketFactory())
    }

    @RetryingTest(5)
    @Flaky
    fun testHeadAfterPut() {
        class ErringRequestBody : ClientRequestBody {
            override fun contentType(): MediaType = "application/xml".toMediaType()

            override fun contentByteSize(): Long = -1L

            override fun writeTo(destination: Writer) {
                destination.write("<el")
                destination.flush()
                throw JayoException("failed to stream the XML")
            }
        }

        class ValidRequestBody : ClientRequestBody {
            override fun contentType(): MediaType = "application/xml".toMediaType()

            override fun contentByteSize(): Long = -1L

            override fun writeTo(destination: Writer) {
                destination.write("<element/>")
                destination.flush()
            }
        }

        server.enqueue(MockResponse(code = 201))
        server.enqueue(MockResponse(code = 204))
        server.enqueue(MockResponse(code = 204))

        val endpointUrl = server.url("/endpoint").toJayo()

        var request = ClientRequest.builder()
            .url(endpointUrl)
            .header("Content-Type", "application/xml")
            .put(ValidRequestBody())
        client.newCall(request).execute().use {
            assertThat(it.statusCode).isEqualTo(201)
        }

        request = ClientRequest.builder()
            .url(endpointUrl)
            .head()
        client.newCall(request).execute().use {
            assertThat(it.statusCode).isEqualTo(204)
        }

        request = ClientRequest.builder()
            .url(endpointUrl)
            .header("Content-Type", "application/xml")
            .put(ErringRequestBody())
        assertFailsWith<JayoException> {
            client.newCall(request).execute()
        }

        request = ClientRequest.builder()
            .url(endpointUrl)
            .head()

        client.newCall(request).execute().use {
            assertThat(it.statusCode).isEqualTo(204)
        }
    }

    @Test
    fun staleConnectionNotReusedForNonIdempotentRequest() {
        platform.assumeNotBouncyCastle()
        platform.assumeNotConscrypt()
        staleConnectionNotReusedForNonIdempotentRequestTest()
    }

    @Test
    fun staleConnectionNotReusedForNonIdempotentRequestBouncyCastle() {
        platform.assumeBouncyCastle()
        staleConnectionNotReusedForNonIdempotentRequestTest()
    }

    @Test
    fun staleConnectionNotReusedForNonIdempotentRequestConscrypt() {
        platform.assumeConscrypt()
        staleConnectionNotReusedForNonIdempotentRequestTest()
    }

    private fun staleConnectionNotReusedForNonIdempotentRequestTest() {
        // Capture the connection so that we can later make it stale.
        var connection: RealConnection? = null
        client =
            client
                .newBuilder()
                .addNetworkInterceptor { chain ->
                    connection = chain.connection() as RealConnection
                    chain.proceed(chain.request())
                }.build()

        server.enqueue(
            MockResponse
                .Builder()
                .body("a")
                .onResponseEnd(
                    CloseSocket(
                        closeSocket = false,
                        shutdownOutput = true,
                    ),
                ).build()
        )
        server.enqueue(MockResponse(body = "b"))

        val requestA = ClientRequest.get(server.url("/").toJayo())
        val responseA = client.newCall(requestA).execute()

        assertThat(responseA.body.string()).isEqualTo("a")
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)

        // Give the socket a chance to become stale.
        connection!!.idleAtNs -= IDLE_CONNECTION_HEALTHY_NS
        Thread.sleep(250)

        val requestB =
            ClientRequest.builder()
                .url(server.url("/").toJayo())
                .post("b".toRequestBody("text/plain".toMediaType()))
        val responseB = client.newCall(requestB).execute()
        assertThat(responseB.body.string()).isEqualTo("b")
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
    }

    /** Confirm suppressed exceptions that occur while connecting are returned. */
    @Test
    fun connectExceptionsAreReturnedAsSuppressed() {
        val proxy = Proxy.http(TestUtils.UNREACHABLE_ADDRESS_IPV4)
        server.close()

        client =
            client
                .newBuilder()
                .proxies(Proxies.of(proxy))
                .networkConfig {
                    it.connectTimeout(Duration.ofMillis(1))
                }
                .build()

        val request = ClientRequest.get(server.url("/").toJayo())

        // exception thrown from ConnectPlan.connectNetworkSocket
        assertFailsWith<JayoException> {
            client.newCall(request).execute()
        }.also { expected ->
            expected.assertSuppressed {
                val suppressed = it.single()
                assertThat(suppressed).isInstanceOf(JayoException::class.java)
                assertThat(suppressed).isNotSameAs(expected)
            }
        }
    }

    /** Confirm suppressed exceptions that occur after connecting are returned. */
    @Test
    fun httpExceptionsAreReturnedAsSuppressed() {
        server.enqueue(MockResponse.Builder().onRequestStart(CloseSocket()).build())
        server.enqueue(MockResponse.Builder().onRequestStart(CloseSocket()).build())

        client =
            client
                .newBuilder()
                .dns(DoubleInetAddressDns()) // Two routes, so we get two failures.
                .build()

        val request = ClientRequest.get(server.url("/").toJayo())

        // exception thrown from RetryAndFollowUpInterceptor.intercept
        assertFailsWith<JayoException> {
            client.newCall(request).execute()
        }.also { expected ->
            expected.assertSuppressed {
                val suppressed = it.single()
                assertThat(suppressed).isInstanceOf(JayoException::class.java)
                assertThat(suppressed).isNotSameAs(expected)
            }
        }
    }

    @Test
    fun responseRequestIsLastRedirect() {
        server.enqueue(
            MockResponse(
                code = 302,
                headers = headersOf("Location", "/b"),
            ),
        )
        server.enqueue(MockResponse())

        val request = ClientRequest.get(server.url("/").toJayo())
        val call = client.newCall(request)
        val response = call.execute()

        assertThat(response.request.url.encodedPath).isEqualTo("/b")
        assertThat(response.request.headers).isEqualTo(Headers.EMPTY)
    }
}
