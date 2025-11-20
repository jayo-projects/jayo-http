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

package jayo.http.logging.internal

import jayo.http.*
import jayo.http.logging.LoggingEventListener
import jayo.network.JayoUnknownHostException
import jayo.tls.ClientTlsSocket
import jayo.tls.JayoTlsException
import jayo.tools.JayoTlsUtils
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.Protocol
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.net.UnknownHostException

class LoggingEventListenerTest {
    @RegisterExtension
    val clientTestRule = JayoHttpClientTestRule()

    @StartStop
    private val server = MockWebServer()

    private var handshakeCertificates = JayoTlsUtils.localhost()
    private val logRecorder =
        HttpLoggingInterceptorTest.LogRecorder(
            prefix = Regex("""\[\d+ ms] """),
        )
    private val loggingEventListenerFactory = LoggingEventListener.Factory(logRecorder)
    private lateinit var client: JayoHttpClient
    private lateinit var url: HttpUrl

    @BeforeEach
    fun setUp() {
        client =
            clientTestRule
                .newClientBuilder()
                .eventListenerFactory(loggingEventListenerFactory)
                .tlsConfig(ClientTlsSocket.builder(handshakeCertificates))
                .retryOnConnectionFailure(false)
                .build()
        url = server.url("/").toJayo()
    }

    @Test
    fun get() {
        server.enqueue(
            MockResponse
                .Builder()
                .body("Hello!")
                .setHeader("Content-Type", PLAIN)
                .build(),
        )
        val response = client.newCall(request().get()).execute()
        assertThat(response.body).isNotNull()
        response.body.bytes()
        logRecorder
            .assertLogMatch(Regex("""callStart: ClientRequest\{method=GET, url=$url\}"""))
            .assertLogMatch(Regex("""proxySelected: $url proxy=null"""))
            .assertLogMatch(Regex("""dnsStart: ${url.host}"""))
            .assertLogMatch(Regex("""dnsEnd: \[.+]"""))
            .assertLogMatch(Regex("""connectStart: ${url.host}/.+ proxy=null"""))
            .assertLogMatch(Regex("""connectEnd: http/1.1"""))
            .assertLogMatch(
                Regex(
                    """connectionAcquired: Connection\{${url.host}:\d+, proxy=null hostAddress=${url.host}/.+ cipherSuite=none protocol=http/1\.1\}""",
                ),
            ).assertLogMatch(Regex("""requestHeadersStart"""))
            .assertLogMatch(Regex("""requestHeadersEnd"""))
            .assertLogMatch(Regex("""responseHeadersStart"""))
            .assertLogMatch(Regex("""responseHeadersEnd: ClientResponse\{protocol=http/1\.1, statusCode=200, statusMessage=OK, url=$url\}"""))
            .assertLogMatch(Regex("""responseBodyStart"""))
            .assertLogMatch(Regex("""responseBodyEnd: byteCount=6"""))
            .assertLogMatch(Regex("""connectionReleased"""))
            .assertLogMatch(Regex("""callEnd"""))
            .assertNoMoreLogs()
    }

    @Test
    fun post() {
        server.enqueue(MockResponse())
        client.newCall(request().post("Hello!".toRequestBody(PLAIN))).execute()
        logRecorder
            .assertLogMatch(Regex("""callStart: ClientRequest\{method=POST, url=$url\}"""))
            .assertLogMatch(Regex("""proxySelected: $url proxy=null"""))
            .assertLogMatch(Regex("""dnsStart: ${url.host}"""))
            .assertLogMatch(Regex("""dnsEnd: \[.+]"""))
            .assertLogMatch(Regex("""connectStart: ${url.host}/.+ proxy=null"""))
            .assertLogMatch(Regex("""connectEnd: http/1.1"""))
            .assertLogMatch(
                Regex(
                    """connectionAcquired: Connection\{${url.host}:\d+, proxy=null hostAddress=${url.host}/.+ cipherSuite=none protocol=http/1\.1\}""",
                ),
            ).assertLogMatch(Regex("""requestHeadersStart"""))
            .assertLogMatch(Regex("""requestHeadersEnd"""))
            .assertLogMatch(Regex("""requestBodyStart"""))
            .assertLogMatch(Regex("""requestBodyEnd: byteCount=6"""))
            .assertLogMatch(Regex("""responseHeadersStart"""))
            .assertLogMatch(Regex("""responseHeadersEnd: ClientResponse\{protocol=http/1\.1, statusCode=200, statusMessage=OK, url=$url\}"""))
            .assertLogMatch(Regex("""responseBodyStart"""))
            .assertLogMatch(Regex("""responseBodyEnd: byteCount=0"""))
            .assertLogMatch(Regex("""connectionReleased"""))
            .assertLogMatch(Regex("""callEnd"""))
            .assertNoMoreLogs()
    }

    @Test
    fun secureGet() {
        server.useHttps(handshakeCertificates.sslSocketFactory())
        url = server.url("/").toJayo()
        server.enqueue(MockResponse())
        val response = client.newCall(request().get()).execute()
        assertThat(response.body).isNotNull()
        response.body.bytes()
        logRecorder
            .assertLogMatch(Regex("""callStart: ClientRequest\{method=GET, url=$url\}"""))
            .assertLogMatch(Regex("""proxySelected: $url proxy=null"""))
            .assertLogMatch(Regex("""dnsStart: ${url.host}"""))
            .assertLogMatch(Regex("""dnsEnd: \[.+]"""))
            .assertLogMatch(Regex("""connectStart: ${url.host}/.+ proxy=null"""))
            .assertLogMatch(Regex("""secureConnectStart"""))
            .assertLogMatch(
                Regex(
                    """secureConnectEnd: Handshake\{tlsVersion=TLSv1.[23], cipherSuite=TLS_.*, localCertificates=\[], peerCertificates=\[CN=localhost]\}""",
                ),
            ).assertLogMatch(Regex("""connectEnd: h2"""))
            .assertLogMatch(
                Regex("""connectionAcquired: Connection\{${url.host}:\d+, proxy=null hostAddress=${url.host}/.+ cipherSuite=.+ protocol=h2\}"""),
            ).assertLogMatch(Regex("""requestHeadersStart"""))
            .assertLogMatch(Regex("""requestHeadersEnd"""))
            .assertLogMatch(Regex("""responseHeadersStart"""))
            .assertLogMatch(Regex("""responseHeadersEnd: ClientResponse\{protocol=h2, statusCode=200, statusMessage=, url=$url\}"""))
            .assertLogMatch(Regex("""responseBodyStart"""))
            .assertLogMatch(Regex("""responseBodyEnd: byteCount=0"""))
            .assertLogMatch(Regex("""connectionReleased"""))
            .assertLogMatch(Regex("""callEnd"""))
            .assertNoMoreLogs()
    }

    @Test
    fun dnsFail() {
        client =
            JayoHttpClient.builder()
                .dns { _ -> throw UnknownHostException("reason") }
                .eventListenerFactory(loggingEventListenerFactory)
                .build()
        assertThatThrownBy { client.newCall(request().get()).execute() }
            .isInstanceOf(JayoUnknownHostException::class.java)
        logRecorder
            .assertLogMatch(Regex("""callStart: ClientRequest\{method=GET, url=$url\}"""))
            .assertLogMatch(Regex("""proxySelected: $url proxy=null"""))
            .assertLogMatch(Regex("""dnsStart: ${url.host}"""))
            .assertLogMatch(Regex("""callFailed: jayo.network.JayoUnknownHostException: reason"""))
            .assertNoMoreLogs()
    }

    @Test
    fun connectFail() {
        server.useHttps(handshakeCertificates.sslSocketFactory())
        server.protocols = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
        server.enqueue(
            MockResponse
                .Builder()
                .failHandshake()
                .build(),
        )
        url = server.url("/").toJayo()
        assertThatThrownBy { client.newCall(request().get()).execute() }
            .isInstanceOf(JayoTlsException::class.java)
        logRecorder
            .assertLogMatch(Regex("""callStart: ClientRequest\{method=GET, url=$url\}"""))
            .assertLogMatch(Regex("""proxySelected: $url proxy=null"""))
            .assertLogMatch(Regex("""dnsStart: ${url.host}"""))
            .assertLogMatch(Regex("""dnsEnd: \[.+]"""))
            .assertLogMatch(Regex("""connectStart: ${url.host}/.+ proxy=null"""))
            .assertLogMatch(Regex("""secureConnectStart"""))
            .assertLogMatch(Regex("""connectFailed: null jayo.tls.JayoTlsException: .*(?:Unexpected handshake message: client_hello|Handshake message sequence violation, 1|Read error|Handshake failed|unexpected_message\(10\)).*"""))
            .assertLogMatch(
                Regex(
                    """callFailed: jayo.tls.JayoTlsException: .*(?:Unexpected handshake message: client_hello|Handshake message sequence violation, 1|Read error|Handshake failed|unexpected_message\(10\)).*""",
                ),
            ).assertNoMoreLogs()
    }

    @Test
    fun testCacheEvents() {
        val request = ClientRequest.builder().url(url).get()
        val call = client.newCall(request)
        val response =
            ClientResponse.builder()
                .request(request)
                .statusCode(200)
                .statusMessage("")
                .protocol(jayo.tls.Protocol.HTTP_2)
                .build()
        val listener = loggingEventListenerFactory.create(call)
        listener.cacheConditionalHit(call, response)
        listener.cacheHit(call, response)
        listener.cacheMiss(call)
        listener.satisfactionFailure(call, response)
        logRecorder
            .assertLogMatch(Regex("""cacheConditionalHit: ClientResponse\{protocol=h2, statusCode=200, statusMessage=, url=$url\}"""))
            .assertLogMatch(Regex("""cacheHit: ClientResponse\{protocol=h2, statusCode=200, statusMessage=, url=$url\}"""))
            .assertLogMatch(Regex("""cacheMiss"""))
            .assertLogMatch(Regex("""satisfactionFailure: ClientResponse\{protocol=h2, statusCode=200, statusMessage=, url=$url\}"""))
            .assertNoMoreLogs()
    }

    private fun request(): ClientRequest.Builder = ClientRequest.builder().url(url)

    companion object {
        private val PLAIN = "text/plain".toMediaType()
    }
}
