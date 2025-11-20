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

package jayo.http.internal.http

import jayo.JayoProtocolException
import jayo.buffered
import jayo.http.*
import jayo.http.CallEvent.*
import jayo.http.internal.RealClientResponse
import jayo.http.internal.duplex.MockSocketHandler
import jayo.http.internal.http.HttpStatusCodes.HTTP_SWITCHING_PROTOCOLS
import jayo.http.internal.toOkhttp
import jayo.tls.ClientTlsSocket
import jayo.tls.Protocol
import jayo.tools.JayoTlsUtils
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertFailsWith

class HttpUpgradesTest {
    @RegisterExtension
    val clientTestRule = JayoHttpClientTestRule()

    @StartStop
    private val server = MockWebServer()

    private var eventRecorder = EventRecorder()
    private var client =
        clientTestRule
            .newClientBuilder()
            .eventListenerFactory(clientTestRule.wrap(eventRecorder))
            .build()

    fun executeAndCheckUpgrade(request: ClientRequest) {
        val socketHandler =
            MockSocketHandler()
                .apply {
                    receiveRequest("client says hello\n")
                    sendResponse("server says hello\n")
                    receiveRequest("client says goodbye\n")
                    sendResponse("server says goodbye\n")
                    exhaustResponse()
                    exhaustRequest()
                }
        server.enqueue(socketHandler.upgradeResponse())

        client
            .newCall(request)
            .execute()
            .use { response ->
                assertThat(response.statusCode).isEqualTo(HTTP_SWITCHING_PROTOCOLS)
                val socket = (response as RealClientResponse).socket!!
                socket.writer.buffered().use { sink ->
                    socket.reader.buffered().use { source ->
                        sink.write("client says hello\n")
                        sink.flush()

                        assertThat(source.readLine()).isEqualTo("server says hello")

                        sink.write("client says goodbye\n")
                        sink.flush()

                        assertThat(source.readLine()).isEqualTo("server says goodbye")

                        assertThat(source.exhausted()).isTrue()
                    }
                }
                socketHandler.awaitSuccess()
            }
    }

    @Test
    fun upgrade() {
        executeAndCheckUpgrade(upgradeRequest())
    }

    @Test
    fun upgradeWithEmptyRequestBody() {
        executeAndCheckUpgrade(
            upgradeRequest().newBuilder()
                .method("POST", ClientRequestBody.EMPTY)
        )
    }

    @Test
    fun upgradeWithNonEmptyRequestBody() {
        executeAndCheckUpgrade(
            upgradeRequest()
                .newBuilder()
                .method("POST", "Hello".toRequestBody())
        )
    }

    @Test
    fun upgradeHttps() {
        enableTls(Protocol.HTTP_1_1)
        upgrade()
    }

    @Test
    fun upgradeRefusedByServer() {
        server.enqueue(MockResponse(body = "normal request"))
        val requestWithUpgrade =
            ClientRequest.builder()
                .url(server.url("/").toJayo())
                .header("Connection", "upgrade")
                .get()
        client.newCall(requestWithUpgrade).execute().use { response ->
            assertThat(response.statusCode).isEqualTo(200)
            assertThat((response as RealClientResponse).socket).isNull()
            assertThat(response.body.string()).isEqualTo("normal request")
        }
        // Confirm there's no RequestBodyStart/RequestBodyEnd on failed upgrades.
        assertThat(eventRecorder.recordedEventTypes()).containsExactly(
            CallStart::class,
            ProxySelected::class,
            DnsStart::class,
            DnsEnd::class,
            ConnectStart::class,
            ConnectEnd::class,
            ConnectionAcquired::class,
            RequestHeadersStart::class,
            RequestHeadersEnd::class,
            ResponseHeadersStart::class,
            ResponseHeadersEnd::class,
            FollowUpDecision::class,
            ResponseBodyStart::class,
            ResponseBodyEnd::class,
            ConnectionReleased::class,
            CallEnd::class,
        )
    }

    @Test
    fun upgradeForbiddenOnHttp2() {
        enableTls(Protocol.HTTP_2, Protocol.HTTP_1_1)
        val socketHandler = MockSocketHandler()
        server.enqueue(socketHandler.upgradeResponse())
        val requestWithUpgrade =
            ClientRequest.builder()
                .url(server.url("/").toJayo())
                .header("Connection", "upgrade")
                .get()
        assertFailsWith<JayoProtocolException> {
            client.newCall(requestWithUpgrade).execute()
        }
    }

    @Test
    fun upgradesOnReusedConnection() {
        server.enqueue(MockResponse(body = "normal request"))
        client.newCall(ClientRequest.get(server.url("/").toJayo()))
            .execute().use { response ->
                assertThat(response.body.string()).isEqualTo("normal request")
            }

        upgrade()

        assertThat(server.takeRequest().connectionIndex).isEqualTo(0)
        assertThat(server.takeRequest().connectionIndex).isEqualTo(0)
    }

    @Test
    fun cannotReuseConnectionAfterUpgrade() {
        upgrade()

        server.enqueue(MockResponse(body = "normal request"))
        client.newCall(ClientRequest.get(server.url("/").toJayo()))
            .execute().use { response ->
                assertThat(response.body.string()).isEqualTo("normal request")
            }

        assertThat(server.takeRequest().connectionIndex).isEqualTo(0)
        assertThat(server.takeRequest().connectionIndex).isEqualTo(1)
    }

    @Test
    fun upgradeEventsWithoutRequestBody() {
        upgrade()

        assertThat(eventRecorder.recordedEventTypes()).containsExactly(
            CallStart::class,
            ProxySelected::class,
            DnsStart::class,
            DnsEnd::class,
            ConnectStart::class,
            ConnectEnd::class,
            ConnectionAcquired::class,
            RequestHeadersStart::class,
            RequestHeadersEnd::class,
            ResponseHeadersStart::class,
            ResponseHeadersEnd::class,
            FollowUpDecision::class,
            RequestBodyStart::class,
            ResponseBodyStart::class,
            ResponseBodyEnd::class,
            RequestBodyEnd::class,
            ConnectionReleased::class,
            CallEnd::class,
        )
    }

    @Test
    fun upgradeEventsWithEmptyRequestBody() {
        upgradeWithEmptyRequestBody()

        assertThat(eventRecorder.recordedEventTypes()).containsExactly(
            CallStart::class,
            ProxySelected::class,
            DnsStart::class,
            DnsEnd::class,
            ConnectStart::class,
            ConnectEnd::class,
            ConnectionAcquired::class,
            RequestHeadersStart::class,
            RequestHeadersEnd::class,
            RequestBodyStart::class,
            RequestBodyEnd::class,
            ResponseHeadersStart::class,
            ResponseHeadersEnd::class,
            FollowUpDecision::class,
            RequestBodyStart::class,
            ResponseBodyStart::class,
            ResponseBodyEnd::class,
            RequestBodyEnd::class,
            ConnectionReleased::class,
            CallEnd::class,
        )
    }

    @Test
    fun upgradeEventsWithNonEmptyRequestBody() {
        upgradeWithNonEmptyRequestBody()

        assertThat(eventRecorder.recordedEventTypes()).containsExactly(
            CallStart::class,
            ProxySelected::class,
            DnsStart::class,
            DnsEnd::class,
            ConnectStart::class,
            ConnectEnd::class,
            ConnectionAcquired::class,
            RequestHeadersStart::class,
            RequestHeadersEnd::class,
            RequestBodyStart::class,
            RequestBodyEnd::class,
            ResponseHeadersStart::class,
            ResponseHeadersEnd::class,
            FollowUpDecision::class,
            RequestBodyStart::class,
            ResponseBodyStart::class,
            ResponseBodyEnd::class,
            RequestBodyEnd::class,
            ConnectionReleased::class,
            CallEnd::class,
        )
    }

    private fun enableTls(vararg protocols: Protocol) {
        val handshakeCertificates = JayoTlsUtils.localhost()
        client =
            client
                .newBuilder()
                .protocols(protocols.toList())
                .tlsConfig(ClientTlsSocket.builder(handshakeCertificates))
                .hostnameVerifier(RecordingHostnameVerifier())
                .build()
        server.useHttps(handshakeCertificates.sslSocketFactory())
        server.protocols = protocols.toList().toOkhttp()
    }

    private fun upgradeRequest() =
        ClientRequest.builder()
            .url(server.url("/").toJayo())
            .headers(Headers.of("Connection", "upgrade"))
            .get()

    private fun MockSocketHandler.upgradeResponse() =
        MockResponse
            .Builder()
            .code(HTTP_SWITCHING_PROTOCOLS)
            .addHeader("Connection", "upgrade")
            .socketHandler(this)
            .build()
}
