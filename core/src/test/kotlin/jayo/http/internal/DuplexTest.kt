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

import jayo.*
import jayo.http.*
import jayo.http.CallEvent.*
import jayo.http.Credentials.basic
import jayo.http.http2.JayoStreamResetException
import jayo.http.internal.duplex.AsyncRequestBody
import jayo.http.internal.duplex.MockSocketHandler
import jayo.tls.ClientTlsSocket
import jayo.tls.JssePlatformRule
import jayo.tls.Protocol
import jayo.tools.JayoTlsUtils
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.Headers.Companion.headersOf
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.net.HttpURLConnection
import java.time.Duration
import java.util.concurrent.*
import kotlin.time.Duration.Companion.milliseconds

@Timeout(30)
class DuplexTest {
    @RegisterExtension
    val platform = JssePlatformRule()

    @RegisterExtension
    var clientTestRule = JayoHttpClientTestRule()

    @StartStop
    private val server = MockWebServer()

    private var eventRecorder = EventRecorder()
    private var client =
        clientTestRule
            .newClientBuilder()
            .eventListenerFactory(clientTestRule.wrap(eventRecorder))
            .build()
    private val executorService = Executors.newScheduledThreadPool(1)

    @AfterEach
    fun tearDown() {
        executorService.shutdown()
    }

    @Test
    fun http1DoesntSupportDuplex() {
        val call =
            client.newCall(
                ClientRequest.builder()
                    .url(server.url("/").toJayo())
                    .post(AsyncRequestBody())
            )
        assertThatThrownBy { call.execute() }
            .isInstanceOf(JayoProtocolException::class.java)
            .hasMessage("Duplex connections are not supported for HTTP/1")
    }

    @Test
    fun trueDuplexClientWritesFirst() {
        enableProtocol(Protocol.HTTP_2)
        val body =
            MockSocketHandler()
                .receiveRequest("request A\n")
                .sendResponse("response B\n")
                .receiveRequest("request C\n")
                .sendResponse("response D\n")
                .receiveRequest("request E\n")
                .sendResponse("response F\n")
                .exhaustRequest()
                .exhaustResponse()
        server.enqueue(
            MockResponse
                .Builder()
                .clearHeaders()
                .socketHandler(body)
                .build(),
        )
        val call =
            client.newCall(
                ClientRequest.builder()
                    .url(server.url("/").toJayo())
                    .post(AsyncRequestBody())
            )
        call.execute().use { response ->
            val requestBody = (call.request().body as AsyncRequestBody?)!!.takeWriter()
            requestBody.write("request A\n")
            requestBody.flush()
            val responseBody = response.body.reader()
            assertThat(responseBody.readLine())
                .isEqualTo("response B")
            requestBody.write("request C\n")
            requestBody.flush()
            assertThat(responseBody.readLine())
                .isEqualTo("response D")
            requestBody.write("request E\n")
            requestBody.flush()
            assertThat(responseBody.readLine())
                .isEqualTo("response F")
            requestBody.close()
            assertThat(responseBody.readLine()).isNull()
        }
        body.awaitSuccess()
    }

    @Test
    fun trueDuplexServerWritesFirst() {
        enableProtocol(Protocol.HTTP_2)
        val body =
            MockSocketHandler()
                .sendResponse("response A\n")
                .receiveRequest("request B\n")
                .sendResponse("response C\n")
                .receiveRequest("request D\n")
                .sendResponse("response E\n")
                .receiveRequest("request F\n")
                .exhaustResponse()
                .exhaustRequest()
        server.enqueue(
            MockResponse
                .Builder()
                .clearHeaders()
                .socketHandler(body)
                .build(),
        )
        val call =
            client.newCall(
                ClientRequest.builder()
                    .url(server.url("/").toJayo())
                    .post(AsyncRequestBody())
            )
        call.execute().use { response ->
            val requestBody = (call.request().body as AsyncRequestBody?)!!.takeWriter()
            val responseBody = response.body.reader()
            assertThat(responseBody.readLine())
                .isEqualTo("response A")
            requestBody.write("request B\n")
            requestBody.flush()
            assertThat(responseBody.readLine())
                .isEqualTo("response C")
            requestBody.write("request D\n")
            requestBody.flush()
            assertThat(responseBody.readLine())
                .isEqualTo("response E")
            requestBody.write("request F\n")
            requestBody.flush()
            assertThat(responseBody.readLine()).isNull()
            requestBody.close()
        }
        body.awaitSuccess()
    }

    @Test
    fun clientReadsHeadersDataTrailers() {
        enableProtocol(Protocol.HTTP_2)
        val body =
            MockSocketHandler()
                .sendResponse("ok")
                .exhaustResponse()
        server.enqueue(
            MockResponse
                .Builder()
                .clearHeaders()
                .addHeader("h1", "v1")
                .addHeader("h2", "v2")
                .trailers(headersOf("trailers", "boom"))
                .socketHandler(body)
                .build(),
        )
        val call =
            client.newCall(
                ClientRequest.builder()
                    .url(server.url("/").toJayo())
                    .get(),
            )
        call.execute().use { response ->
            assertThat(response.headers)
                .isEqualTo(Headers.of("h1", "v1", "h2", "v2"))
            val responseBody = response.body.reader()
            assertThat(responseBody.readString(2)).isEqualTo("ok")
            assertThat(responseBody.exhausted()).isTrue()
            assertThat(response.trailers()).isEqualTo(Headers.of("trailers", "boom"))
        }
        body.awaitSuccess()
    }

    @Test
    fun serverReadsHeadersData() {
        enableProtocol(Protocol.HTTP_2)
        val body =
            MockSocketHandler()
                .exhaustResponse()
                .receiveRequest("hey\n")
                .receiveRequest("whats going on\n")
                .exhaustRequest()
        server.enqueue(
            MockResponse
                .Builder()
                .clearHeaders()
                .addHeader("h1", "v1")
                .addHeader("h2", "v2")
                .socketHandler(body)
                .build(),
        )
        val request =
            ClientRequest.builder()
                .url(server.url("/").toJayo())
                .post(AsyncRequestBody())
        val call = client.newCall(request)
        call.execute().use {
            val writer = (request.body as AsyncRequestBody?)!!.takeWriter()
            writer.write("hey\n")
            writer.write("whats going on\n")
            writer.close()
        }
        body.awaitSuccess()
    }

    @Test
    fun requestBodyEndsAfterResponseBody() {
        platform.assumeBouncyCastle() // whatever works

        enableProtocol(Protocol.HTTP_2)
        val body =
            MockSocketHandler()
                .exhaustResponse()
                .receiveRequest("request A\n")
                .exhaustRequest()
        server.enqueue(
            MockResponse
                .Builder()
                .clearHeaders()
                .socketHandler(body)
                .build(),
        )
        val call =
            client.newCall(
                ClientRequest.builder()
                    .url(server.url("/").toJayo())
                    .post(AsyncRequestBody())
            )
        call.execute().use { response ->
            val responseBody = response.body.reader()
            assertThat(responseBody.exhausted()).isTrue()
            val requestBody = (call.request().body as AsyncRequestBody?)!!.takeWriter()
            requestBody.write("request A\n")
            requestBody.close()
        }
        body.awaitSuccess()
        assertThat(eventRecorder.recordedEventTypes()).containsExactly(
            CallStart::class,
            ProxySelected::class,
            DnsStart::class,
            DnsEnd::class,
            ConnectStart::class,
            SecureConnectStart::class,
            SecureConnectEnd::class,
            ConnectEnd::class,
            ConnectionAcquired::class,
            RequestHeadersStart::class,
            RequestHeadersEnd::class,
            RequestBodyStart::class,
            ResponseHeadersStart::class,
            ResponseHeadersEnd::class,
            FollowUpDecision::class,
            ResponseBodyStart::class,
            ResponseBodyEnd::class,
            RequestBodyEnd::class,
            ConnectionReleased::class,
            CallEnd::class,
        )
    }

    @Test
    fun duplexWith100Continue() {
        enableProtocol(Protocol.HTTP_2)
        val body =
            MockSocketHandler()
                .receiveRequest("request body\n")
                .sendResponse("response body\n")
                .exhaustRequest()
        server.enqueue(
            MockResponse
                .Builder()
                .clearHeaders()
                .add100Continue()
                .socketHandler(body)
                .build(),
        )
        val call =
            client.newCall(
                ClientRequest.builder()
                    .url(server.url("/").toJayo())
                    .header("Expect", "100-continue")
                    .post(AsyncRequestBody())
            )
        call.execute().use { response ->
            val requestBody = (call.request().body as AsyncRequestBody?)!!.takeWriter()
            requestBody.write("request body\n")
            requestBody.flush()
            val responseBody = response.body.reader()
            assertThat(responseBody.readLine())
                .isEqualTo("response body")
            requestBody.close()
            assertThat(responseBody.readLine()).isNull()
        }
        body.awaitSuccess()
    }

    /**
     * Duplex calls that have follow-ups are weird. By the time we know there's a follow-up, we've already split off
     * another thread to stream the request body. Because we permit at most one exchange at a time, we break the request
     * stream out from under that writer.
     */
    @Tag("no-ci")
    @Test
    fun duplexWithRedirect() {
        platform.assumeConscrypt() // whatever works

        enableProtocol(Protocol.HTTP_2)
        val duplexResponseSent = CountDownLatch(1)
        val requestHeadersEndListener =
            object : EventListener() {
                override fun requestHeadersEnd(
                    call: Call,
                    request: ClientRequest,
                ) {
                    // Wait for the server to send the duplex response before acting on the 301 response
                    // and resetting the stream.
                    duplexResponseSent.await()
                }
            }
        client =
            client
                .newBuilder()
                .eventListener(eventRecorder.eventListener + requestHeadersEndListener)
                .build()
        val body =
            MockSocketHandler()
                .sendResponse("/a has moved!\n", duplexResponseSent)
                .requestIOException()
                .exhaustResponse()
        server.enqueue(
            MockResponse
                .Builder()
                .clearHeaders()
                .code(HttpURLConnection.HTTP_MOVED_PERM)
                .addHeader("Location: /b")
                .socketHandler(body)
                .build(),
        )
        server.enqueue(
            MockResponse
                .Builder()
                .body("this is /b")
                .build(),
        )
        val call =
            client.newCall(
                ClientRequest.builder()
                    .url(server.url("/").toJayo())
                    .post(AsyncRequestBody())
            )
        call.execute().use { response ->
            val responseBody = response.body.reader()
            assertThat(responseBody.readLine())
                .isEqualTo("this is /b")
        }
        val requestBody = (call.request().body as AsyncRequestBody?)!!.takeWriter()
        assertThatThrownBy {
            requestBody.write("request body\n")
            requestBody.flush()
        }.isInstanceOf(JayoStreamResetException::class.java)
            .hasMessage("stream was reset: CANCEL")

        body.awaitSuccess()
        assertThat(eventRecorder.recordedEventTypes()).containsExactly(
            CallStart::class,
            ProxySelected::class,
            DnsStart::class,
            DnsEnd::class,
            ConnectStart::class,
            SecureConnectStart::class,
            SecureConnectEnd::class,
            ConnectEnd::class,
            ConnectionAcquired::class,
            RequestHeadersStart::class,
            RequestHeadersEnd::class,
            RequestBodyStart::class,
            ResponseHeadersStart::class,
            ResponseHeadersEnd::class,
            ResponseBodyStart::class,
            ResponseBodyEnd::class,
            FollowUpDecision::class,
            RequestHeadersStart::class,
            RequestHeadersEnd::class,
            ResponseHeadersStart::class,
            ResponseHeadersEnd::class,
            FollowUpDecision::class,
            ResponseBodyStart::class,
            ResponseBodyEnd::class,
            ConnectionReleased::class,
            CallEnd::class,
            RequestFailed::class,
        )
        assertThat(eventRecorder.findEvent<FollowUpDecision>()).matches {
            it.nextRequest != null
        }
    }

    /**
     * Auth requires follow-ups. Unlike redirects, the auth follow-up also has a request body. This test makes a single
     * call with two duplex requests!
     */
    @Test
    fun duplexWithAuthChallenge() {
        enableProtocol(Protocol.HTTP_2)
        val credential = basic("jesse", "secret")
        client =
            client
                .newBuilder()
                .authenticator(RecordingJayoAuthenticator(credential, null))
                .build()
        val body1 =
            MockSocketHandler()
                .sendResponse("please authenticate!\n")
                .requestIOException()
                .exhaustResponse()
        server.enqueue(
            MockResponse
                .Builder()
                .clearHeaders()
                .code(HttpURLConnection.HTTP_UNAUTHORIZED)
                .socketHandler(body1)
                .build(),
        )
        val body =
            MockSocketHandler()
                .sendResponse("response body\n")
                .exhaustResponse()
                .receiveRequest("request body\n")
                .exhaustRequest()
        server.enqueue(
            MockResponse
                .Builder()
                .clearHeaders()
                .socketHandler(body)
                .build(),
        )
        val call =
            client.newCall(
                ClientRequest.builder()
                    .url(server.url("/").toJayo())
                    .post(AsyncRequestBody())
            )
        val response2 = call.execute()

        // The first duplex request is detached with violence.
        val requestBody1 = (call.request().body as AsyncRequestBody?)!!.takeWriter()
        assertThatThrownBy {
            requestBody1.write("not authenticated\n")
            requestBody1.flush()
        }.isInstanceOf(JayoStreamResetException::class.java)
            .hasMessage("stream was reset: CANCEL")
        body1.awaitSuccess()

        // Second duplex request proceeds normally.
        val requestBody2 = (call.request().body as AsyncRequestBody?)!!.takeWriter()
        requestBody2.write("request body\n")
        requestBody2.close()
        val responseBody2 = response2.body.reader()
        assertThat(responseBody2.readLine())
            .isEqualTo("response body")
        assertThat(responseBody2.exhausted()).isTrue()
        body.awaitSuccess()

        // No more requests attempted!
        (call.request().body as AsyncRequestBody?)!!.assertNoMoreWriters()
    }

    @Test
    fun fullCallTimeoutAppliesToSetup() {
        enableProtocol(Protocol.HTTP_2)
        server.enqueue(
            MockResponse
                .Builder()
                .headersDelay(500, TimeUnit.MILLISECONDS)
                .build(),
        )
        val request =
            ClientRequest.builder()
                .url(server.url("/").toJayo())
                .post(AsyncRequestBody())
        val call = client.newCall(request)
        assertThatThrownBy {
            cancelScope(250.milliseconds) {
                call.execute()
            }
        }.isInstanceOf(JayoTimeoutException::class.java)
        assertThat(call.isCanceled()).isTrue()
    }

    @Test
    fun fullCallTimeoutDoesNotApplyOnceConnected() {
        enableProtocol(Protocol.HTTP_2)
        val body =
            MockSocketHandler()
                .sleep(250, TimeUnit.MILLISECONDS)
                .sendResponse("response A\n")
                .sleep(500, TimeUnit.MILLISECONDS)
                .sendResponse("response B\n")
                .receiveRequest("request C\n")
                .exhaustResponse()
                .exhaustRequest()
        server.enqueue(
            MockResponse
                .Builder()
                .clearHeaders()
                .socketHandler(body)
                .build(),
        )
        val request =
            ClientRequest.builder()
                .url(server.url("/").toJayo())
                .post(AsyncRequestBody())
        val call = client.newCall(request)
        val response = cancelScope(500.milliseconds) { // Long enough for the first TLS handshake.
            call.execute()
        }
        response.use { response ->
            val requestBody = (call.request().body as AsyncRequestBody?)!!.takeWriter()
            val responseBody = response.body.reader()
            assertThat(responseBody.readLine())
                .isEqualTo("response A")
            assertThat(responseBody.readLine())
                .isEqualTo("response B")
            requestBody.write("request C\n")
            requestBody.close()
            assertThat(responseBody.readLine()).isNull()
        }
        body.awaitSuccess()
    }

    @Test
    fun duplexWithRewriteInterceptors() {
        enableProtocol(Protocol.HTTP_2)
        val body =
            MockSocketHandler()
                .receiveRequest("REQUEST A\n")
                .sendResponse("response B\n")
                .exhaustRequest()
                .exhaustResponse()
        server.enqueue(
            MockResponse
                .Builder()
                .clearHeaders()
                .socketHandler(body)
                .build(),
        )
        client =
            client
                .newBuilder()
                .addInterceptor(UppercaseRequestInterceptor())
                .addInterceptor(UppercaseResponseInterceptor())
                .build()
        val call =
            client.newCall(
                ClientRequest.builder()
                    .url(server.url("/").toJayo())
                    .post(AsyncRequestBody())
            )
        call.execute().use { response ->
            val requestBody = (call.request().body as AsyncRequestBody?)!!.takeWriter()
            requestBody.write("request A\n")
            requestBody.flush()
            val responseBody = response.body.reader()
            assertThat(responseBody.readLine())
                .isEqualTo("RESPONSE B")
            requestBody.close()
            assertThat(responseBody.readLine()).isNull()
        }
        body.awaitSuccess()
    }

    /**
     * Jayo HTTP currently doesn't implement failing the request body stream independently of failing the corresponding
     * response body stream. This is necessary if we want servers to be able to stop inbound data and send an early 400
     * before the request body completes.
     *
     * This test sends a slow request canceled by the server. It expects the response to still be readable after the
     * request stream is canceled.
     */
    @Test
    fun serverCancelsRequestBodyAndSendsResponseBody() {
        client =
            client
                .newBuilder()
                .retryOnConnectionFailure(false)
                .build()
        val log: BlockingQueue<String> = LinkedBlockingQueue()
        enableProtocol(Protocol.HTTP_2)
        val body =
            MockSocketHandler()
                .sendResponse("success!")
                .exhaustResponse()
                .cancelStream()
        server.enqueue(
            MockResponse
                .Builder()
                .clearHeaders()
                .socketHandler(body)
                .build(),
        )
        val call =
            client.newCall(
                ClientRequest.builder()
                    .url(server.url("/").toJayo())
                    .post(
                        object : ClientRequestBody {
                            override fun contentType(): MediaType? = null

                            override fun contentByteSize(): Long = -1L

                            override fun writeTo(destination: Writer) {
                                try {
                                    for (i in 0..9) {
                                        destination.write(".")
                                        destination.flush()
                                        Thread.sleep(100)
                                    }
                                } catch (je: JayoException) {
                                    log.add(je.toString())
                                    throw je
                                } catch (e: Exception) {
                                    log.add(e.toString())
                                }
                            }
                        },
                    )
            )
        call.execute().use { response ->
            assertThat(response.body.string()).isEqualTo("success!")
        }
        body.awaitSuccess()
        assertThat(log.take())
            .contains("JayoStreamResetException: stream was reset: ")
    }

    /**
     * We delay sending the last byte of the request body 750 ms. The 1000 ms read timeout should only elapse 500 ms
     * after the request body is sent.
     */
    @Test
    fun headersReadTimeoutDoesNotStartUntilLastRequestBodyByteFire() {
        enableProtocol(Protocol.HTTP_2)
        server.enqueue(
            MockResponse
                .Builder()
                .headersDelay(750, TimeUnit.MILLISECONDS)
                .build(),
        )
        val request =
            ClientRequest.builder()
                .url(server.url("/").toJayo())
                .post(
                    DelayedRequestBody(
                        "hello".toRequestBody(null),
                        Duration.ofMillis(750)
                    )
                )
        client =
            client
                .newBuilder()
                .readTimeout(Duration.ofMillis(500))
                .build()
        val call = client.newCall(request)
        assertThatThrownBy {
            call.execute()
        }.isInstanceOf(JayoTimeoutException::class.java)
    }

    /** Same as the previous test, but the server stalls sending the response body.  */
    @Test
    fun bodyReadTimeoutDoesNotStartUntilLastRequestBodyByteFire() {
        enableProtocol(Protocol.HTTP_2)
        server.enqueue(
            MockResponse
                .Builder()
                .bodyDelay(750, TimeUnit.MILLISECONDS)
                .body("this should never be received")
                .build(),
        )
        val request =
            ClientRequest.builder()
                .url(server.url("/").toJayo())
                .post(
                    DelayedRequestBody(
                        "hello".toRequestBody(null),
                        Duration.ofMillis(750)
                    )
                )
        client =
            client
                .newBuilder()
                .readTimeout(Duration.ofMillis(500))
                .build()
        val call = client.newCall(request)
        val response = call.execute()
        assertThatThrownBy {
            response.body.string()
        }.isInstanceOf(JayoTimeoutException::class.java)
    }

    /**
     * We delay sending the last byte of the request body 750 ms. The 500 ms read timeout shouldn't elapse because it
     * shouldn't start until the request body is sent.
     */
    @Test
    fun headersReadTimeoutDoesNotStartUntilLastRequestBodyByteNoFire() {
        enableProtocol(Protocol.HTTP_2)
        server.enqueue(
            MockResponse
                .Builder()
                .headersDelay(250, TimeUnit.MILLISECONDS)
                .build(),
        )
        val request =
            ClientRequest.builder()
                .url(server.url("/").toJayo())
                .post(
                    DelayedRequestBody(
                        "hello".toRequestBody(null),
                        Duration.ofMillis(750)
                    )
                )
        client =
            client
                .newBuilder()
                .readTimeout(Duration.ofMillis(500))
                .build()
        val call = client.newCall(request)
        val response = call.execute()
        assertThat(response.isSuccessful).isTrue()
    }

    /**
     * We delay sending the last byte of the request body 750 ms. The 500 ms read timeout shouldn't elapse because it
     * shouldn't start until the request body is sent.
     */
    @Test
    fun bodyReadTimeoutDoesNotStartUntilLastRequestBodyByteNoFire() {
        enableProtocol(Protocol.HTTP_2)
        server.enqueue(
            MockResponse
                .Builder()
                .bodyDelay(250, TimeUnit.MILLISECONDS)
                .body("success")
                .build(),
        )
        val request =
            ClientRequest.builder()
                .url(server.url("/").toJayo())
                .post(
                    DelayedRequestBody(
                        "hello".toRequestBody(null),
                        Duration.ofMillis(750)
                    )
                )
        client =
            client
                .newBuilder()
                .readTimeout(Duration.ofMillis(500))
                .build()
        val call = client.newCall(request)
        val response = call.execute()
        assertThat(response.body.string()).isEqualTo("success")
    }

    /**
     * Tests that use this will fail unless the boot classpath is set.
     * Ex. `-Xbootclasspath/p:/tmp/alpn-boot-8.0.0.v20140317`
     */
    private fun enableProtocol(protocol: Protocol) {
        enableTls()
        client =
            client
                .newBuilder()
                .protocols(listOf(protocol, Protocol.HTTP_1_1))
                .build()
        server.protocols = client.protocols.toOkhttp()
    }

    private fun enableTls() {
        val handshakeCertificates = JayoTlsUtils.localhost()

        client =
            client
                .newBuilder()
                .tlsClientBuilder(ClientTlsSocket.builder(handshakeCertificates))
                .hostnameVerifier(RecordingHostnameVerifier())
                .build()
        server.useHttps(handshakeCertificates.sslSocketFactory())
    }

    private inner class DelayedRequestBody(
        private val delegate: ClientRequestBody,
        delay: Duration,
    ) : ClientRequestBody {
        private val delayMillis = delay.toMillis()

        override fun contentType() = delegate.contentType()

        override fun contentByteSize() = -1L

        override fun isDuplex() = true

        override fun writeTo(destination: Writer) {
            executorService.schedule({
                delegate.writeTo(destination)
                destination.close()
            }, delayMillis, TimeUnit.MILLISECONDS)
        }
    }
}
