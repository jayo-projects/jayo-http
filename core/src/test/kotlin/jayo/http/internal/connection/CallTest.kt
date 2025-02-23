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

import jayo.*
import jayo.http.*
import jayo.http.CallEvent.*
import jayo.http.CertificatePinner.pin
import jayo.http.CookieJar.javaNetCookieJar
import jayo.http.Credentials.basic
import jayo.http.EventListener
import jayo.http.internal.*
import jayo.http.internal.TestUtils.awaitGarbageCollection
import jayo.http.internal.Utils.USER_AGENT
import jayo.http.internal.http.HttpStatusCodes.HTTP_EARLY_HINTS
import jayo.http.internal.http.HttpStatusCodes.HTTP_PROCESSING
import jayo.http.testing.Flaky
import jayo.internal.GzipRawWriter
import jayo.network.JayoConnectException
import jayo.network.JayoUnknownServiceException
import jayo.tls.*
import jayo.tools.JayoTlsUtils
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.QueueDispatcher
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect.*
import mockwebserver3.junit5.StartStop
import okhttp3.Headers.Companion.headersOf
import okio.ByteString
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.fail
import java.net.*
import java.time.Duration
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertFailsWith

@Timeout(30)
class CallTest {
    @RegisterExtension
    val platform = JssePlatformRule()

    @RegisterExtension
    val clientTestRule = JayoHttpClientTestRule()

    @RegisterExtension
    val testLogHandler = TestLogHandler("jayo.http.JayoHttpClient")

    @StartStop
    private val server = MockWebServer()

    @StartStop
    private val server2 = MockWebServer()

    private var listener = RecordingEventListener()
    private var client =
        clientTestRule
            .newClientBuilder()
            .eventListenerFactory(clientTestRule.wrap(listener))
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
    private val callback = RecordingCallback()
//    private val fileSystem = FakeFileSystem()
//    private val cache =
//        Cache(
//            fileSystem = LoggingFilesystem(fileSystem),
//            directory = "/cache".toPath(),
//            maxSize = Int.MAX_VALUE.toLong(),
//        )
//
//    @AfterEach
//    fun tearDown() {
//        cache.close()
//        fileSystem.checkNoOpenFiles()
//    }

    @Test
    fun get() {
        server.enqueue(
            MockResponse
                .Builder()
                .body("abc")
                .clearHeaders()
                .addHeader("content-type: text/plain")
                .addHeader("content-length", "3")
                .build(),
        )
        val sentAt = System.currentTimeMillis()
        val recordedResponse = executeSynchronously("/", "User-Agent", "SyncApiTest")
        val receivedAt = System.currentTimeMillis()
        recordedResponse
            .assertCode(200)
            .assertSuccessful()
            .assertHeaders(
                Headers.builder()
                    .add("content-type", "text/plain")
                    .add("content-length", "3")
                    .build(),
            ).assertBody("abc")
            .assertSentRequestAtMillis(sentAt, receivedAt)
            .assertReceivedResponseAtMillis(sentAt, receivedAt)
        val recordedRequest = server.takeRequest()
        assertThat(recordedRequest.method).isEqualTo("GET")
        assertThat(recordedRequest.headers["User-Agent"]).isEqualTo("SyncApiTest")
        assertThat(recordedRequest.body).isNull()
        assertThat(recordedRequest.headers["Content-Length"]).isNull()
    }

    @Test
    fun buildRequestUsingHttpUrl() {
        server.enqueue(MockResponse())
        executeSynchronously("/").assertSuccessful()
    }

    @Test
    fun invalidScheme() {
        val requestBuilder = ClientRequest.builder()
        assertFailsWith<IllegalArgumentException> {
            requestBuilder.url("ftp://hostname/path")
        }.also { expected ->
            assertThat(expected.message).isEqualTo("Expected URL scheme 'http' or 'https' but was 'ftp'")
        }
    }

    @Test
    fun invalidPort() {
        val requestBuilder = ClientRequest.builder()
        assertFailsWith<IllegalArgumentException> {
            requestBuilder.url("http://localhost:65536/")
        }.also { expected ->
            assertThat(expected.message).isEqualTo("Invalid URL port: \"65536\"")
        }
    }

    @Test
    fun getReturns500() {
        server.enqueue(MockResponse(code = 500))
        executeSynchronously("/")
            .assertCode(500)
            .assertNotSuccessful()
    }

    @Test
    fun get_HTTP_2() {
        enableProtocol(Protocol.HTTP_2)
        get()
    }

    @Test
    fun get_HTTPS() {
        enableTls()
        get()
    }

    @Test
    fun repeatedHeaderNames() {
        server.enqueue(
            MockResponse(
                headers =
                    headersOf(
                        "B",
                        "123",
                        "B",
                        "234",
                    ),
            ),
        )
        executeSynchronously("/", "A", "345", "A", "456")
            .assertCode(200)
            .assertHeader("B", "123", "234")
        val recordedRequest = server.takeRequest()
        assertThat(recordedRequest.headers.values("A")).containsExactly("345", "456")
    }

    @Test
    fun repeatedHeaderNames_HTTP_2() {
        enableProtocol(Protocol.HTTP_2)
        repeatedHeaderNames()
    }

    @Test
    fun getWithRequestBody() {
        server.enqueue(MockResponse())
        assertFailsWith<IllegalArgumentException> {
            client.newBuilder()
            ClientRequest.builder()
                .url(server.url("/").toJayo())
                .method("GET", "abc".toRequestBody("text/plain".toMediaType()))
        }
    }

    @Test
    fun head() {
        server.enqueue(
            MockResponse(
                headers = headersOf("Content-Type", "text/plain"),
            ),
        )
        val request = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .header("User-Agent", "SyncApiTest")
            .head()
        executeSynchronously(request)
            .assertCode(200)
            .assertHeader("Content-Type", "text/plain")
        val recordedRequest = server.takeRequest()
        assertThat(recordedRequest.method).isEqualTo("HEAD")
        assertThat(recordedRequest.headers["User-Agent"]).isEqualTo("SyncApiTest")
        assertThat(recordedRequest.body).isNull()
        assertThat(recordedRequest.headers["Content-Length"]).isNull()
    }

    @Test
    fun headResponseContentLengthIsIgnored() {
        platform.assumeConscrypt() // whatever assertion on platform works, it is required for exchangeIndex check

        server.enqueue(
            MockResponse
                .Builder()
                .clearHeaders()
                .addHeader("Content-Length", "100")
                .build(),
        )
        server.enqueue(
            MockResponse(body = "abc"),
        )
        val headRequest = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .head()
        val response = client.newCall(headRequest).execute()
        assertThat(response.status.code).isEqualTo(200)
        assertArrayEquals(ByteArray(0), response.body.bytes())
        val getRequest = ClientRequest.get(server.url("/").toJayo())
        executeSynchronously(getRequest)
            .assertCode(200)
            .assertBody("abc")
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)
    }

    @Test
    fun headResponseContentEncodingIsIgnored() {
        platform.assumeConscrypt() // whatever assertion on platform works, it is required for exchangeIndex check

        server.enqueue(
            MockResponse
                .Builder()
                .clearHeaders()
                .addHeader("Content-Encoding", "chunked")
                .build(),
        )
        server.enqueue(MockResponse(body = "abc"))
        val headRequest = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .head()
        executeSynchronously(headRequest)
            .assertCode(200)
            .assertHeader("Content-Encoding", "chunked")
            .assertBody("")
        val getRequest = ClientRequest.get(server.url("/").toJayo())
        executeSynchronously(getRequest)
            .assertCode(200)
            .assertBody("abc")
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)
    }

    @Test
    fun head_HTTPS() {
        enableTls()
        head()
    }

    @Test
    fun head_HTTP_2() {
        enableProtocol(Protocol.HTTP_2)
        head()
    }

    @Test
    fun post() {
        server.enqueue(MockResponse(body = "abc"))
        val request = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .post("def".toRequestBody("text/plain".toMediaType()))
        executeSynchronously(request)
            .assertCode(200)
            .assertBody("abc")
        val recordedRequest = server.takeRequest()
        assertThat(recordedRequest.method).isEqualTo("POST")
        assertThat(recordedRequest.body?.utf8()).isEqualTo("def")
        assertThat(recordedRequest.headers["Content-Length"]).isEqualTo("3")
        assertThat(recordedRequest.headers["Content-Type"]).isEqualTo("text/plain; charset=utf-8")
    }

    @Test
    fun post_HTTPS() {
        enableTls()
        post()
    }

    @Test
    fun post_HTTP_2() {
        enableProtocol(Protocol.HTTP_2)
        post()
    }

    @Test
    fun postZeroLength() {
        server.enqueue(MockResponse(body = "abc"))
        val request = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .post(ByteArray(0).toRequestBody(null))
        executeSynchronously(request)
            .assertCode(200)
            .assertBody("abc")
        val recordedRequest = server.takeRequest()
        assertThat(recordedRequest.method).isEqualTo("POST")
        assertThat(recordedRequest.body?.size).isEqualTo(0)
        assertThat(recordedRequest.headers["Content-Length"]).isEqualTo("0")
        assertThat(recordedRequest.headers["Content-Type"]).isNull()
    }

    @Test
    fun postZerolength_HTTPS() {
        enableTls()
        postZeroLength()
    }

    @Test
    fun postZerolength_HTTP_2() {
        enableProtocol(Protocol.HTTP_2)
        postZeroLength()
    }

    @Test
    fun postBodyRetransmittedAfterAuthorizationFail() {
        postBodyRetransmittedAfterAuthorizationFail("abc", server)
    }

    @Test
    fun postBodyRetransmittedAfterAuthorizationFail_HTTPS() {
        enableTls()
        postBodyRetransmittedAfterAuthorizationFail("abc", server)
    }

    @Test
    fun postBodyRetransmittedAfterAuthorizationFail_HTTP_2() {
        enableProtocol(Protocol.HTTP_2)
        postBodyRetransmittedAfterAuthorizationFail("abc", server)
    }

    /** Don't explode when resending an empty post. */
    @Test
    fun postEmptyBodyRetransmittedAfterAuthorizationFail() {
        postBodyRetransmittedAfterAuthorizationFail("", server)
    }

    @Test
    fun postEmptyBodyRetransmittedAfterAuthorizationFail_HTTPS() {
        enableTls()
        postBodyRetransmittedAfterAuthorizationFail("", server)
    }

    @Test
    fun postEmptyBodyRetransmittedAfterAuthorizationFail_HTTP_2() {
        enableProtocol(Protocol.HTTP_2)
        postBodyRetransmittedAfterAuthorizationFail("", server)
    }

    private fun postBodyRetransmittedAfterAuthorizationFail(body: String, server: MockWebServer) {
        server.enqueue(MockResponse(code = 401))
        server.enqueue(MockResponse())
        val request = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .post(body.toRequestBody(null))
        val credential = basic("pull-vert", "secret")
        client = client.newBuilder()
            .authenticator(RecordingJayoAuthenticator(credential, null))
            .build()
        val response = client.newCall(request).execute()
        assertThat(response.status.code).isEqualTo(200)
        response.body.close()
        val recordedRequest1 = server.takeRequest()
        assertThat(recordedRequest1.method).isEqualTo("POST")
        assertThat(recordedRequest1.body?.utf8()).isEqualTo(body)
        assertThat(recordedRequest1.headers["Authorization"]).isNull()
        val recordedRequest2 = server.takeRequest()
        assertThat(recordedRequest2.method).isEqualTo("POST")
        assertThat(recordedRequest2.body?.utf8()).isEqualTo(body)
        assertThat(recordedRequest2.headers["Authorization"]).isEqualTo(credential)
    }

    @Test
    fun attemptAuthorization20Times() {
        repeat(20) {
            server.enqueue(MockResponse(code = 401))
        }
        server.enqueue(MockResponse(body = "Success!"))
        val credential = basic("pull-vert", "secret")
        client =
            client
                .newBuilder()
                .authenticator(RecordingJayoAuthenticator(credential, null))
                .build()
        executeSynchronously("/")
            .assertCode(200)
            .assertBody("Success!")
    }

    @Test
    fun doesNotAttemptAuthorization21Times() {
        repeat(21) {
            server.enqueue(MockResponse(code = 401))
        }
        val credential = basic("pull-vert", "secret")
        client =
            client
                .newBuilder()
                .authenticator(RecordingJayoAuthenticator(credential, null))
                .build()
        assertFailsWith<JayoProtocolException> {
            client.newCall(
                ClientRequest.builder()
                    .url(server.url("/0").toJayo())
                    .get()
            ).execute()
        }.also { expected ->
            assertThat(expected.message).isEqualTo("Too many follow-up requests: 21")
        }
    }

    @Test
    fun authenticateWithNoConnection() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(401)
                .addHeader("Connection", "close")
                .onResponseEnd(ShutdownConnection)
                .build()
        )
        val authenticator = RecordingJayoAuthenticator(null, null)
        client =
            client
                .newBuilder()
                .authenticator(authenticator)
                .build()
        executeSynchronously("/")
            .assertCode(401)
        assertThat(authenticator.onlyRoute()).isNotNull()
    }

    @Test
    fun delete() {
        server.enqueue(MockResponse(body = "abc"))
        val request = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .delete()
        executeSynchronously(request)
            .assertCode(200)
            .assertBody("abc")
        val recordedRequest = server.takeRequest()
        assertThat(recordedRequest.method).isEqualTo("DELETE")
        assertThat(recordedRequest.body?.size).isEqualTo(0)
        assertThat(recordedRequest.headers["Content-Length"]).isEqualTo("0")
        assertThat(recordedRequest.headers["Content-Type"]).isNull()
    }

    @Test
    fun delete_HTTPS() {
        enableTls()
        delete()
    }

    @Test
    fun delete_HTTP_2() {
        enableProtocol(Protocol.HTTP_2)
        delete()
    }

    @Test
    fun deleteWithRequestBody() {
        server.enqueue(MockResponse(body = "abc"))
        val request = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .delete("def".toRequestBody("text/plain".toMediaType()))
        executeSynchronously(request)
            .assertCode(200)
            .assertBody("abc")
        val recordedRequest = server.takeRequest()
        assertThat(recordedRequest.method).isEqualTo("DELETE")
        assertThat(recordedRequest.body?.utf8()).isEqualTo("def")
    }

    @Test
    fun put() {
        server.enqueue(MockResponse(body = "abc"))
        val request = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .put("def".toRequestBody("text/plain".toMediaType()))
        executeSynchronously(request)
            .assertCode(200)
            .assertBody("abc")
        val recordedRequest = server.takeRequest()
        assertThat(recordedRequest.method).isEqualTo("PUT")
        assertThat(recordedRequest.body?.utf8()).isEqualTo("def")
        assertThat(recordedRequest.headers["Content-Length"]).isEqualTo("3")
        assertThat(recordedRequest.headers["Content-Type"]).isEqualTo(
            "text/plain; charset=utf-8",
        )
    }

    @Test
    fun put_HTTPS() {
        enableTls()
        put()
    }

    @Test
    fun put_HTTP_2() {
        enableProtocol(Protocol.HTTP_2)
        put()
    }

    @Test
    fun patch() {
        server.enqueue(MockResponse(body = "abc"))
        val request = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .patch("def".toRequestBody("text/plain".toMediaType()))
        executeSynchronously(request)
            .assertCode(200)
            .assertBody("abc")
        val recordedRequest = server.takeRequest()
        assertThat(recordedRequest.method).isEqualTo("PATCH")
        assertThat(recordedRequest.body?.utf8()).isEqualTo("def")
        assertThat(recordedRequest.headers["Content-Length"]).isEqualTo("3")
        assertThat(recordedRequest.headers["Content-Type"]).isEqualTo("text/plain; charset=utf-8")
    }

    @Test
    fun patch_HTTPS() {
        enableTls()
        patch()
    }

    @Test
    fun patch_HTTP_2() {
        enableProtocol(Protocol.HTTP_2)
        patch()
    }

    @Test
    fun customMethodWithBody() {
        server.enqueue(MockResponse(body = "abc"))
        val request = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .method("CUSTOM", "def".toRequestBody("text/plain".toMediaType()))
        executeSynchronously(request)
            .assertCode(200)
            .assertBody("abc")
        val recordedRequest = server.takeRequest()
        assertThat(recordedRequest.method).isEqualTo("CUSTOM")
        assertThat(recordedRequest.body?.utf8()).isEqualTo("def")
        assertThat(recordedRequest.headers["Content-Length"]).isEqualTo("3")
        assertThat(recordedRequest.headers["Content-Type"])
            .isEqualTo("text/plain; charset=utf-8")
    }

    @Test
    fun unspecifiedRequestBodyContentTypeDoesNotGetDefault() {
        server.enqueue(MockResponse())
        val request = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .post("abc".toRequestBody(null))
        executeSynchronously(request).assertCode(200)
        val recordedRequest = server.takeRequest()
        assertThat(recordedRequest.headers["Content-Type"]).isNull()
        assertThat(recordedRequest.headers["Content-Length"]).isEqualTo("3")
        assertThat(recordedRequest.body?.utf8()).isEqualTo("abc")
    }

    @Test
    fun illegalToExecuteTwice() {
        server.enqueue(
            MockResponse(
                headers = headersOf("Content-Type", "text/plain"),
                body = "abc",
            ),
        )
        val request = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .header("User-Agent", "SyncApiTest")
            .get()
        val call = client.newCall(request)
        val response = call.execute()
        response.body.close()
        assertFailsWith<IllegalStateException> {
            call.execute()
        }.also { expected ->
            assertThat(expected.message).isEqualTo("Already Executed")
        }
        assertFailsWith<IllegalStateException> {
            call.enqueue(callback)
        }.also { expected ->
            assertThat(expected.message).isEqualTo("Already Executed")
        }
        assertThat(server.takeRequest().headers["User-Agent"]).isEqualTo("SyncApiTest")
    }

    @Test
    fun illegalToExecuteTwice_Async() {
        server.enqueue(
            MockResponse(
                headers = headersOf("Content-Type", "text/plain"),
                body = "abc",
            ),
        )
        val request = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .header("User-Agent", "SyncApiTest")
            .get()
        val call = client.newCall(request)
        call.enqueue(callback)
        assertFailsWith<IllegalStateException> {
            call.execute()
        }.also { expected ->
            assertThat(expected.message).isEqualTo("Already Executed")
        }
        assertFailsWith<IllegalStateException> {
            call.enqueue(callback)
        }.also { expected ->
            assertThat(expected.message).isEqualTo("Already Executed")
        }
        assertThat(server.takeRequest().headers["User-Agent"]).isEqualTo("SyncApiTest")
        callback.await(request.url).assertSuccessful()
    }

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
    fun legalToExecuteTwiceCloning_Async() {
        server.enqueue(MockResponse(body = "abc"))
        server.enqueue(MockResponse(body = "def"))
        val request = ClientRequest.get(server.url("/").toJayo())
        val call = client.newCall(request)
        call.enqueue(callback)
        val cloned = call.clone()
        cloned.enqueue(callback)
        val firstResponse = callback.await(request.url).assertSuccessful()
        val secondResponse = callback.await(request.url).assertSuccessful()
        val bodies: MutableSet<String?> = kotlin.collections.LinkedHashSet()
        bodies.add(firstResponse.body)
        bodies.add(secondResponse.body)
        assertThat(bodies).contains("abc")
        assertThat(bodies).contains("def")
    }

    @Test
    fun get_Async() {
        server.enqueue(
            MockResponse(
                headers = headersOf("Content-Type", "text/plain"),
                body = "abc",
            ),
        )
        val request = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .header("User-Agent", "AsyncApiTest")
            .get()
        client.newCall(request).enqueue(callback)
        callback
            .await(request.url)
            .assertCode(200)
            .assertHeader("Content-Type", "text/plain")
            .assertBody("abc")
        assertThat(server.takeRequest().headers["User-Agent"]).isEqualTo("AsyncApiTest")
    }

    @Test
    fun exceptionThrownByOnResponseIsRedactedAndLogged() {
        server.enqueue(MockResponse())
        val request = ClientRequest.get(server.url("/secret").toJayo())
        client.newCall(request).enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    je: JayoException,
                ) {
                    fail("")
                }

                override fun onResponse(
                    call: Call,
                    response: ClientResponse,
                ): Unit = throw JayoException("a")
            },
        )
        assertThat(testLogHandler.take())
            .isEqualTo("INFO: Callback failure for call to " + server.url("/") + "...")
    }

    @Test
    fun connectionPooling() {
        platform.assumeConscrypt() // whatever assertion on platform works, it is required for exchangeIndex check

        server.enqueue(MockResponse(body = "abc"))
        server.enqueue(MockResponse(body = "def"))
        server.enqueue(MockResponse(body = "ghi"))
        executeSynchronously("/a").assertBody("abc")
        executeSynchronously("/b").assertBody("def")
        executeSynchronously("/c").assertBody("ghi")
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(2)
    }

    @Test
    fun connectionPoolingWithClientBuiltOffProxy() {
        client = JayoHttpClient.builder()
            .proxies(server.proxyAddress.toJayo())
            .build()
        server.enqueue(MockResponse(body = "abc"))
        server.enqueue(MockResponse(body = "def"))
        server.enqueue(MockResponse(body = "ghi"))
        client = client.newBuilder().build()
        executeSynchronously("/a").assertBody("abc")
        client = client.newBuilder().build()
        executeSynchronously("/b").assertBody("def")
        client = client.newBuilder().build()
        executeSynchronously("/c").assertBody("ghi")
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(2)
    }

    @Test
    fun connectionPooling_Async() {
        platform.assumeConscrypt() // whatever assertion on platform works, it is required for exchangeIndex check

        server.enqueue(MockResponse(body = "abc"))
        server.enqueue(MockResponse(body = "def"))
        server.enqueue(MockResponse(body = "ghi"))
        client.newCall(
            ClientRequest.builder()
                .url(server.url("/a").toJayo()).get()
        )
            .enqueue(callback)
        callback.await(server.url("/a").toJayo()).assertBody("abc")
        client.newCall(
            ClientRequest.builder()
                .url(server.url("/b").toJayo()).get()
        )
            .enqueue(callback)
        callback.await(server.url("/b").toJayo()).assertBody("def")
        client.newCall(
            ClientRequest.builder()
                .url(server.url("/c").toJayo()).get()
        )
            .enqueue(callback)
        callback.await(server.url("/c").toJayo()).assertBody("ghi")
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(2)
    }

    @Test
    fun connectionPoolingWithFreshClientSamePool() {
        platform.assumeConscrypt() // whatever assertion on platform works, it is required for exchangeIndex check

        server.enqueue(MockResponse(body = "abc"))
        server.enqueue(MockResponse(body = "def"))
        server.enqueue(MockResponse(body = "ghi"))
        client = JayoHttpClient.builder()
            .connectionPool(client.connectionPool)
            .proxies(server.proxyAddress.toJayo())
            .build()
        executeSynchronously("/a").assertBody("abc")
        client = JayoHttpClient.builder()
            .connectionPool(client.connectionPool)
            .proxies(server.proxyAddress.toJayo())
            .build()
        executeSynchronously("/b").assertBody("def")
        client = JayoHttpClient.builder()
            .connectionPool(client.connectionPool)
            .proxies(server.proxyAddress.toJayo())
            .build()
        executeSynchronously("/c").assertBody("ghi")
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(2)
    }

    @Test
    fun connectionReuseWhenResponseBodyConsumed_Async() {
        platform.assumeConscrypt() // whatever assertion on platform works, it is required for exchangeIndex check

        server.enqueue(MockResponse(body = "abc"))
        server.enqueue(MockResponse(body = "def"))
        val request = ClientRequest.builder()
            .url(server.url("/a").toJayo())
            .get()
        client.newCall(request).enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    je: JayoException,
                ): Unit = throw kotlin.AssertionError()

                override fun onResponse(
                    call: Call,
                    response: ClientResponse,
                ) {
                    val bytes = response.body.byteStream()
                    assertThat(bytes.read()).isEqualTo('a'.code)
                    assertThat(bytes.read()).isEqualTo('b'.code)
                    assertThat(bytes.read()).isEqualTo('c'.code)

                    // This request will share a connection with 'A' cause it's all done.
                    client.newCall(
                        ClientRequest.builder()
                            .url(server.url("/b").toJayo()).get()
                    )
                        .enqueue(callback)
                }
            },
        )
        callback.await(server.url("/b").toJayo()).assertCode(200).assertBody("def")
        // New connection.
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
        // Connection reuse!
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)
    }

    @Test
    fun timeoutsUpdatedOnReusedConnections() {
        platform.assumeConscrypt() // whatever assertion on platform works, it is required for exchangeIndex check

        server.enqueue(
            MockResponse
                .Builder()
                .body("ab")
                .throttleBody(1, 750, TimeUnit.MILLISECONDS)
                .build(),
        )

        // Request: time out after 250ms.
        client =
            client
                .newBuilder()
                .readTimeout(Duration.ofMillis(250))
                .build()
        val request = ClientRequest.builder()
            .url(server.url("/b").toJayo())
            .get()
        val response = client.newCall(request).execute()
        val bodySource = response.body.reader()
        assertThat(bodySource.readByte()).isEqualTo('a'.code.toByte())

        // The second byte of this request will be delayed by 750ms so we should time out after 250ms.
        val startNanos = System.nanoTime()
        bodySource.use {
            assertFailsWith<JayoException> {
                bodySource.readByte()
            }.also { expected ->
                // Timed out as expected.
                val elapsedNanos = System.nanoTime() - startNanos
                val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos)
                assertThat(elapsedMillis).isLessThan(500)
            }
        }
    }

    /** https://github.com/square/okhttp/issues/442  */
    @Test
    fun tlsTimeoutsNotRetried() {
        platform.assumeConscrypt() // whatever assertion on platform works

        enableTls()
        server.enqueue(MockResponse.Builder().onResponseStart(Stall).build())
        server.enqueue(MockResponse(body = "unreachable!"))
        client =
            client
                .newBuilder()
                .readTimeout(Duration.ofMillis(100))
                .build()
        val request = ClientRequest.builder().url(server.url("/").toJayo()).get()
        assertFailsWith<JayoInterruptedIOException> {
            // If this succeeds, too many requests were made.
            client.newCall(request).execute()
        }
    }

    /** https://github.com/square/okhttp/issues/4875  */
    @Test
    fun interceptorRecoversWhenRoutesExhausted() {
        platform.assumeConscrypt() // whatever assertion on platform works

        server.enqueue(MockResponse.Builder().onRequestStart(CloseSocket()).build())
        server.enqueue(MockResponse())
        client =
            client
                .newBuilder()
                .addInterceptor { chain: Interceptor.Chain ->
                    try {
                        chain.proceed(chain.request())
                        throw kotlin.AssertionError()
                    } catch (_: JayoException) { // the first call throws an expected exception
                        chain.proceed(chain.request())
                    }
                }.build()
        val request = ClientRequest.get(server.url("/").toJayo())
        executeSynchronously(request)
            .assertCode(200)
    }

    /** https://github.com/square/okhttp/issues/4761  */
    @Test
    fun interceptorCallsProceedWithoutClosingPriorResponse() {
        server.enqueue(
            MockResponse(body = "abc"),
        )
        client =
            clientTestRule
                .newClientBuilder()
                .addInterceptor { chain: Interceptor.Chain ->
                    val response =
                        chain.proceed(
                            chain.request(),
                        )
                    assertFailsWith<IllegalStateException> {
                        chain.proceed(chain.request())
                    }.also { expected ->
                        assertThat(expected.message!!).contains("please call response.close()")
                    }
                    response
                }.build()
        val request = ClientRequest.get(server.url("/").toJayo())
        executeSynchronously(request).assertBody("abc")
    }

    /** https://github.com/square/okhttp/issues/1801  */
    @Test
    fun asyncCallEngineInitialized() {
        val c =
            client
                .newBuilder()
                .addInterceptor { throw JayoException("") }
                .build()
        val request = ClientRequest.builder().url(server.url("/").toJayo()).get()
        c.newCall(request).enqueue(callback)
        val response = callback.await(request.url)
        assertThat(response.request).isEqualTo(request)
    }

    @Test
    fun tls() {
        enableTls()
        server.enqueue(
            MockResponse(
                headers = headersOf("Content-Type", "text/plain"),
                body = "abc",
            ),
        )
        executeSynchronously("/").assertHandshake()
    }

    @Test
    fun tls_Async() {
        enableTls()
        server.enqueue(
            MockResponse(
                headers = headersOf("Content-Type", "text/plain"),
                body = "abc",
            ),
        )
        val request = ClientRequest.get(server.url("/").toJayo())
        client.newCall(request).enqueue(callback)
        callback.await(request.url).assertHandshake()
    }

    @Test
    fun recoverWhenRetryOnConnectionFailureIsTrue() {
        // Set to 2 because the seeding request will count down before the retried request does.
        val requestFinished = CountDownLatch(2)
        val dispatcher: QueueDispatcher =
            object : QueueDispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (peek().onResponseStart is CloseSocket) {
                        requestFinished.await()
                    }
                    return super.dispatch(request)
                }
            }
        dispatcher.enqueue(MockResponse(body = "seed connection pool"))
        dispatcher.enqueue(MockResponse.Builder().onResponseStart(CloseSocket()).build())
        dispatcher.enqueue(MockResponse(body = "retry success"))
        server.dispatcher = dispatcher
        listener =
            object : RecordingEventListener() {
                override fun requestHeadersEnd(
                    call: Call,
                    request: ClientRequest,
                ) {
                    requestFinished.countDown()
                    super.responseHeadersStart(call)
                }
            }
        client =
            client
                .newBuilder()
                .dns(DoubleInetAddressDns())
                .eventListenerFactory(clientTestRule.wrap(listener))
                .build()
        assertThat(client.retryOnConnectionFailure()).isTrue()
        executeSynchronously("/").assertBody("seed connection pool")
        executeSynchronously("/").assertBody("retry success")

        // The call that seeds the connection pool.
        listener.removeUpToEvent(CallEnd::class.java)

        // The ResponseFailed event is not necessarily fatal!
        listener.removeUpToEvent(ConnectionAcquired::class.java)
        listener.removeUpToEvent(ResponseFailed::class.java)
        listener.removeUpToEvent(ConnectionReleased::class.java)
        listener.removeUpToEvent(ConnectionAcquired::class.java)
        listener.removeUpToEvent(ConnectionReleased::class.java)
        listener.removeUpToEvent(CallEnd::class.java)
    }

    @Test
    fun recoverWhenRetryOnConnectionFailureIsTrue_HTTP2() {
        enableProtocol(Protocol.HTTP_2)
        recoverWhenRetryOnConnectionFailureIsTrue()
    }

    @Test
    fun noRecoverWhenRetryOnConnectionFailureIsFalse() {
        platform.assumeConscrypt() // whatever assertion on platform works

        server.enqueue(MockResponse(body = "seed connection pool"))
        server.enqueue(MockResponse.Builder().onResponseStart(CloseSocket()).build())
        server.enqueue(MockResponse(body = "unreachable!"))
        client =
            client
                .newBuilder()
                .dns(DoubleInetAddressDns())
                .retryOnConnectionFailure(false)
                .build()
        executeSynchronously("/").assertBody("seed connection pool")

        // If this succeeds, too many requests were made.
        executeSynchronously("/")
            .assertFailure(JayoException::class.java)
            .assertFailureMatches(
                "stream was reset: CANCEL",
                "unexpected end of stream on " + server.url("/").redact(),
            )
    }

    @Test
    @Flaky
    fun recoverWhenRetryOnConnectionFailureIsFalse_HTTP2() {
        platform.assumeConscrypt() // whatever assertion on platform works

        enableProtocol(Protocol.HTTP_2)
        noRecoverWhenRetryOnConnectionFailureIsFalse()
    }

    @Test
    fun tlsHandshakeFailure_noFallbackByDefault() {
        val handshakeCertificates = JayoTlsUtils.localhost()
        server.useHttps(handshakeCertificates.sslSocketFactory())
        server.enqueue(MockResponse.Builder().failHandshake().build())
        server.enqueue(MockResponse(body = "response that will never be received"))
        val response = executeSynchronously("/")
        response.assertFailure(
            JayoTlsException::class.java,
        )
        assertThat(client.connectionSpecs).doesNotContain(ConnectionSpec.COMPATIBLE_TLS)
    }

    @Test
    fun recoverFromTlsHandshakeFailure() {
        val handshakeCertificates = JayoTlsUtils.localhost()
        server.useHttps(handshakeCertificates.sslSocketFactory())
        server.enqueue(MockResponse.Builder().failHandshake().build())
        server.enqueue(MockResponse(body = "abc"))
        client =
            client
                .newBuilder()
                .hostnameVerifier(RecordingHostnameVerifier())
                .tlsClientBuilder(ClientTlsEndpoint.builder(handshakeCertificates))
                .connectionSpecs(
                    // Attempt MODERN_TLS then fall back to COMPATIBLE_TLS.
                    listOf(
                        ConnectionSpec.MODERN_TLS,
                        ConnectionSpec.COMPATIBLE_TLS
                    ),
                ).build()
        executeSynchronously("/").assertBody("abc")
    }

    @Test
    fun recoverFromTlsHandshakeFailure_Async() {
        val handshakeCertificates = JayoTlsUtils.localhost()
        server.useHttps(handshakeCertificates.sslSocketFactory())
        server.enqueue(MockResponse.Builder().failHandshake().build())
        server.enqueue(MockResponse(body = "abc"))
        client =
            client
                .newBuilder()
                .hostnameVerifier(RecordingHostnameVerifier())
                .tlsClientBuilder(ClientTlsEndpoint.builder(handshakeCertificates))
                .connectionSpecs(
                    // Attempt MODERN_TLS then fall back to COMPATIBLE_TLS.
                    listOf(
                        ConnectionSpec.MODERN_TLS,
                        ConnectionSpec.COMPATIBLE_TLS
                    ),
                ).build()
        val request = ClientRequest.get(server.url("/").toJayo())
        client.newCall(request).enqueue(callback)
        callback.await(request.url).assertBody("abc")
    }

    @Test
    fun noRecoveryFromTlsHandshakeFailureWhenTlsFallbackIsDisabled() {
        val handshakeCertificates = JayoTlsUtils.localhost()
        server.useHttps(handshakeCertificates.sslSocketFactory())
        server.enqueue(MockResponse.Builder().failHandshake().build())
        client =
            client
                .newBuilder()
                .hostnameVerifier(RecordingHostnameVerifier())
                .tlsClientBuilder(ClientTlsEndpoint.builder(handshakeCertificates))
                .connectionSpecs(
                    // Attempt MODERN_TLS then fall back to CLEARTEXT.
                    listOf(
                        ConnectionSpec.MODERN_TLS,
                        ConnectionSpec.CLEARTEXT
                    ),
                ).build()
        val request = ClientRequest.get(server.url("/").toJayo())
        assertFailsWith<JayoTlsException> {
            client.newCall(request).execute()
        }
    }

    @Test
    fun tlsHostnameVerificationFailure() {
        platform.assumeNotBouncyCastle()

        server.enqueue(MockResponse())
        val serverCertificate =
            HeldCertificate.builder()
                .commonName("localhost") // Unusued for hostname verification.
                .addSubjectAlternativeName("wronghostname")
                .build()
        val serverCertificates = ServerHandshakeCertificates
            .builder(serverCertificate)
            .build()
        val clientCertificates = ClientHandshakeCertificates.builder()
            .addTrustedCertificate(serverCertificate.certificate)
            .build()
        client =
            client
                .newBuilder()
                .tlsClientBuilder(ClientTlsEndpoint.builder(clientCertificates))
                .build()
        server.useHttps(serverCertificates.sslSocketFactory())
        executeSynchronously("/")
            .assertFailureMatches("(?s)Hostname localhost not verified.*")
    }

    @Test
    fun cleartextCallsFailWhenCleartextIsDisabled() {
        // Configure the client with only TLS configurations. No cleartext!
        client =
            client
                .newBuilder()
                .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS))
                .build()
        server.enqueue(MockResponse())
        val request = ClientRequest.builder().url(server.url("/").toJayo()).get()
        assertFailsWith<JayoUnknownServiceException> {
            client.newCall(request).execute()
        }.also { expected ->
            assertThat(expected.message).isEqualTo(
                "CLEARTEXT communication not enabled for client",
            )
        }
    }

    @Test
    fun httpsCallsFailWhenProtocolIsH2PriorKnowledge() {
        val handshakeCertificates = JayoTlsUtils.localhost()
        client =
            client
                .newBuilder()
                .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
                .build()
        server.useHttps(handshakeCertificates.sslSocketFactory())
        server.enqueue(MockResponse())
        val call = client.newCall(ClientRequest.get(server.url("/").toJayo()))
        assertFailsWith<JayoUnknownServiceException> {
            call.execute()
        }.also { expected ->
            assertThat(expected.message).isEqualTo("H2_PRIOR_KNOWLEDGE cannot be used with HTTPS")
        }
    }

    @Test
    fun setFollowTlsRedirectsFalse() {
        enableTls()
        server.enqueue(
            MockResponse(
                code = 301,
                headers = headersOf("Location", "http://jayo.dev"),
            ),
        )
        client =
            client
                .newBuilder()
                .followTlsRedirects(false)
                .build()
        val request = ClientRequest.builder().url(server.url("/").toJayo()).get()
        val response = client.newCall(request).execute()
        assertThat(response.status.code).isEqualTo(301)
        response.body.close()
    }

    @Test
    fun matchingPinnedCertificate() {
        platform.assumeConscrypt() // whatever assertion on platform works

        enableTls()
        server.enqueue(MockResponse())
        server.enqueue(MockResponse())

        // Make a first request without certificate pinning. Use it to collect certificates to pin.
        val request1 = ClientRequest.builder().url(server.url("/").toJayo()).get()
        val response1 = client.newCall(request1).execute()
        val certificatePinnerBuilder = CertificatePinner.builder()
        for (certificate in response1.handshake!!.peerCertificates) {
            certificatePinnerBuilder.add(
                server.hostName,
                pin(certificate),
            )
        }
        response1.body.close()

        // Make another request with certificate pinning. It should complete normally.
        client =
            client
                .newBuilder()
                .certificatePinner(certificatePinnerBuilder.build())
                .build()
        val request2 = ClientRequest.builder().url(server.url("/").toJayo()).get()
        val response2 = client.newCall(request2).execute()
        assertThat(response1.handshake).isNotSameAs(
            response2.handshake,
        )
        response2.body.close()
    }

    @Test
    fun unmatchingPinnedCertificate() {
        enableTls()
        server.enqueue(MockResponse())

        // Pin publicobject.com's cert.
        client =
            client
                .newBuilder()
                .certificatePinner(
                    CertificatePinner.builder()
                        .add(server.hostName, "sha256/afwiKY3RxoMmLkuRW1l7QsPZTJPwDS2pdDROQjXw8ig=")
                        .build(),
                ).build()

        // When we pin the wrong certificate, connectivity fails.
        val request = ClientRequest.builder().url(server.url("/").toJayo()).get()
        assertFailsWith<JayoTlsPeerUnverifiedException> {
            client.newCall(request).execute()
        }.also { expected ->
            assertThat(expected.message!!).startsWith("Certificate pinning failure!")
        }
    }

    @Test
    fun post_Async() {
        server.enqueue(MockResponse(body = "abc"))
        val request =
            ClientRequest.builder()
                .url(server.url("/").toJayo())
                .post("def".toRequestBody("text/plain".toMediaType()))
        client.newCall(request).enqueue(callback)
        callback
            .await(request.url)
            .assertCode(200)
            .assertBody("abc")
        val recordedRequest = server.takeRequest()
        assertThat(recordedRequest.body?.utf8()).isEqualTo("def")
        assertThat(recordedRequest.headers["Content-Length"]).isEqualTo("3")
        assertThat(recordedRequest.headers["Content-Type"]).isEqualTo("text/plain; charset=utf-8")
    }

    @Test
    fun serverHalfClosingBeforeResponse() {
        platform.assumeConscrypt() // whatever assertion on platform works, it is required for exchangeIndex check

        server.enqueue(MockResponse(body = "abc"))
        server.enqueue(
            MockResponse
                .Builder()
                .onResponseStart(
                    CloseSocket(
                        closeSocket = false,
                        shutdownOutput = true,
                    ),
                ).build()
        )
        server.enqueue(MockResponse(body = "abc"))

        val client =
            client
                .newBuilder()
                .retryOnConnectionFailure(false)
                .build()

        // Seed the connection pool so we have something that can fail.
        val request1 = ClientRequest.builder().url(server.url("/").toJayo()).get()
        val response1 = client.newCall(request1).execute()
        assertThat(response1.body.string()).isEqualTo("abc")

        listener.clearAllEvents()

        val request2 = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .post("body!".toRequestBody("text/plain".toMediaType()))
        assertFailsWith<JayoException> {
            client.newCall(request2).execute()
        }.also { expected ->
            assertThat(expected.message!!).startsWith("unexpected end of stream on http://")
        }

        assertThat(listener.recordedEventTypes()).containsExactly(
            "CallStart",
            "ConnectionAcquired",
            "RequestHeadersStart",
            "RequestHeadersEnd",
            "RequestBodyStart",
            "RequestBodyEnd",
            "ResponseFailed",
            "RetryDecision",
            "ConnectionReleased",
            "CallFailed",
        )
        assertThat(listener.findEvent<RetryDecision>()).hasFieldOrPropertyWithValue("retry", false)
        listener.clearAllEvents()

        val response3 = client.newCall(request1).execute()
        assertThat(response3.body.string()).isEqualTo("abc")

        assertThat(listener.recordedEventTypes()).containsExactly(
            "CallStart",
            "ProxySelected",
            "DnsStart",
            "DnsEnd",
            "ConnectStart",
            "ConnectEnd",
            "ConnectionAcquired",
            "RequestHeadersStart",
            "RequestHeadersEnd",
            "ResponseHeadersStart",
            "ResponseHeadersEnd",
            "FollowUpDecision",
            "ResponseBodyStart",
            "ResponseBodyEnd",
            "ConnectionReleased",
            "CallEnd",
        )

        val get = server.takeRequest()
        assertThat(get.exchangeIndex).isEqualTo(0)

        val post1 = server.takeRequest()
        assertThat(post1.body?.utf8()).isEqualTo("body!")
        assertThat(post1.exchangeIndex).isEqualTo(1)

        val get2 = server.takeRequest()
        assertThat(get2.exchangeIndex).isEqualTo(0)
    }

    @Test
    fun postBodyRetransmittedOnFailureRecovery() {
        platform.assumeBouncyCastle() // whatever assertion on platform works, it is required for exchangeIndex check

        server.enqueue(MockResponse(body = "abc"))
        server.enqueue(MockResponse.Builder().onResponseStart(CloseSocket()).build())
        server.enqueue(MockResponse(body = "def"))

        // Seed the connection pool so we have something that can fail.
        val request1 = ClientRequest.builder().url(server.url("/").toJayo()).get()
        val response1 = client.newCall(request1).execute()
        assertThat(response1.body.string()).isEqualTo("abc")
        val request2 = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .post("body!".toRequestBody("text/plain".toMediaType()))
        val response2 = client.newCall(request2).execute()
        assertThat(response2.body.string()).isEqualTo("def")
        val get = server.takeRequest()
        assertThat(get.exchangeIndex).isEqualTo(0)
        val post1 = server.takeRequest()
        assertThat(post1.body?.utf8()).isEqualTo("body!")
        assertThat(post1.exchangeIndex).isEqualTo(1)
        val post2 = server.takeRequest()
        assertThat(post2.body?.utf8()).isEqualTo("body!")
        assertThat(post2.exchangeIndex).isEqualTo(0)
    }

    @Test
    fun postBodyRetransmittedOnFailureRecovery_HTTP2() {
        enableProtocol(Protocol.HTTP_2)
        postBodyRetransmittedOnFailureRecovery()
    }

//    @Test
//    fun cacheHit() {
//        server.enqueue(
//            MockResponse(
//                headers =
//                    headersOf(
//                        "ETag",
//                        "v1",
//                        "Cache-Control",
//                        "max-age=60",
//                        "Vary",
//                        "Accept-Charset",
//                    ),
//                body = "A",
//            ),
//        )
//        client =
//            client
//                .newBuilder()
//                .cache(cache)
//                .build()
//
//        // Store a response in the cache.
//        val url = server.url("/")
//        val request1SentAt = System.currentTimeMillis()
//        executeSynchronously("/", "Accept-Language", "fr-CA", "Accept-Charset", "UTF-8")
//            .assertCode(200)
//            .assertBody("A")
//        val request1ReceivedAt = System.currentTimeMillis()
//        assertThat(server.takeRequest().headers["If-None-Match"]).isNull()
//
//        // Hit that stored response. It's different, but Vary says it doesn't matter.
//        Thread.sleep(10) // Make sure the timestamps are unique.
//        val cacheHit =
//            executeSynchronously(
//                "/",
//                "Accept-Language",
//                "en-US",
//                "Accept-Charset",
//                "UTF-8",
//            )
//
//        // Check the merged response. The request is the application's original request.
//        cacheHit
//            .assertCode(200)
//            .assertBody("A")
//            .assertHeaders(
//                Headers
//                    .Builder()
//                    .add("ETag", "v1")
//                    .add("Cache-Control", "max-age=60")
//                    .add("Vary", "Accept-Charset")
//                    .add("Content-Length", "1")
//                    .build(),
//            ).assertRequestUrl(url)
//            .assertRequestHeader("Accept-Language", "en-US")
//            .assertRequestHeader("Accept-Charset", "UTF-8")
//            .assertSentRequestAtMillis(request1SentAt, request1ReceivedAt)
//            .assertReceivedResponseAtMillis(request1SentAt, request1ReceivedAt)
//
//        // Check the cached response. Its request contains only the saved Vary headers.
//        cacheHit
//            .cacheResponse()
//            .assertCode(200)
//            .assertHeaders(
//                Headers
//                    .Builder()
//                    .add("ETag", "v1")
//                    .add("Cache-Control", "max-age=60")
//                    .add("Vary", "Accept-Charset")
//                    .add("Content-Length", "1")
//                    .build(),
//            ).assertRequestMethod("GET")
//            .assertRequestUrl(url)
//            .assertRequestHeader("Accept-Language")
//            .assertRequestHeader("Accept-Charset", "UTF-8")
//            .assertSentRequestAtMillis(request1SentAt, request1ReceivedAt)
//            .assertReceivedResponseAtMillis(request1SentAt, request1ReceivedAt)
//        cacheHit.assertNoNetworkResponse()
//    }
//
//    @Test
//    fun conditionalCacheHit() {
//        server.enqueue(
//            MockResponse(
//                headers =
//                    headersOf(
//                        "ETag",
//                        "v1",
//                        "Vary",
//                        "Accept-Charset",
//                        "Donut",
//                        "a",
//                    ),
//                body = "A",
//            ),
//        )
//        server.enqueue(
//            MockResponse
//                .Builder()
//                .clearHeaders()
//                .addHeader("Donut: b")
//                .code(HttpURLConnection.HTTP_NOT_MODIFIED)
//                .build(),
//        )
//        client =
//            client
//                .newBuilder()
//                .cache(cache)
//                .build()
//
//        // Store a response in the cache.
//        val request1SentAt = System.currentTimeMillis()
//        executeSynchronously("/", "Accept-Language", "fr-CA", "Accept-Charset", "UTF-8")
//            .assertCode(200)
//            .assertHeader("Donut", "a")
//            .assertBody("A")
//        val request1ReceivedAt = System.currentTimeMillis()
//        assertThat(server.takeRequest().headers["If-None-Match"]).isNull()
//
//        // Hit that stored response. It's different, but Vary says it doesn't matter.
//        Thread.sleep(10) // Make sure the timestamps are unique.
//        val request2SentAt = System.currentTimeMillis()
//        val cacheHit =
//            executeSynchronously(
//                "/",
//                "Accept-Language",
//                "en-US",
//                "Accept-Charset",
//                "UTF-8",
//            )
//        val request2ReceivedAt = System.currentTimeMillis()
//        assertThat(server.takeRequest().headers["If-None-Match"]).isEqualTo("v1")
//
//        // Check the merged response. The request is the application's original request.
//        cacheHit
//            .assertCode(200)
//            .assertBody("A")
//            .assertHeader("Donut", "b")
//            .assertRequestUrl(server.url("/"))
//            .assertRequestHeader("Accept-Language", "en-US")
//            .assertRequestHeader("Accept-Charset", "UTF-8")
//            .assertRequestHeader("If-None-Match") // No If-None-Match on the user's request.
//            .assertSentRequestAtMillis(request2SentAt, request2ReceivedAt)
//            .assertReceivedResponseAtMillis(request2SentAt, request2ReceivedAt)
//
//        // Check the cached response. Its request contains only the saved Vary headers.
//        cacheHit
//            .cacheResponse()
//            .assertCode(200)
//            .assertHeader("Donut", "a")
//            .assertHeader("ETag", "v1")
//            .assertRequestUrl(server.url("/"))
//            .assertRequestHeader("Accept-Language") // No Vary on Accept-Language.
//            .assertRequestHeader("Accept-Charset", "UTF-8") // Because of Vary on Accept-Charset.
//            .assertRequestHeader("If-None-Match") // This wasn't present in the original request.
//            .assertSentRequestAtMillis(request1SentAt, request1ReceivedAt)
//            .assertReceivedResponseAtMillis(request1SentAt, request1ReceivedAt)
//
//        // Check the network response. It has the caller's request, plus some caching headers.
//        cacheHit
//            .networkResponse()
//            .assertCode(304)
//            .assertHeader("Donut", "b")
//            .assertRequestHeader("Accept-Language", "en-US")
//            .assertRequestHeader("Accept-Charset", "UTF-8")
//            .assertRequestHeader("If-None-Match", "v1") // If-None-Match in the validation request.
//            .assertSentRequestAtMillis(request2SentAt, request2ReceivedAt)
//            .assertReceivedResponseAtMillis(request2SentAt, request2ReceivedAt)
//    }
//
//    @Test
//    fun conditionalCacheHit_Async() {
//        server.enqueue(
//            MockResponse(
//                headers = headersOf("ETag", "v1"),
//                body = "A",
//            ),
//        )
//        server.enqueue(
//            MockResponse
//                .Builder()
//                .clearHeaders()
//                .code(HttpURLConnection.HTTP_NOT_MODIFIED)
//                .build(),
//        )
//        client =
//            client
//                .newBuilder()
//                .cache(cache)
//                .build()
//        val request1 = ClientRequest.get(server.url("/").toJayo())
//        client.newCall(request1).enqueue(callback)
//        callback.await(request1.url).assertCode(200).assertBody("A")
//        assertThat(server.takeRequest().headers["If-None-Match"]).isNull()
//        val request2 = ClientRequest.get(server.url("/").toJayo())
//        client.newCall(request2).enqueue(callback)
//        callback.await(request2.url).assertCode(200).assertBody("A")
//        assertThat(server.takeRequest().headers["If-None-Match"]).isEqualTo("v1")
//    }
//
//    @Test
//    fun conditionalCacheMiss() {
//        server.enqueue(
//            MockResponse(
//                headers =
//                    headersOf(
//                        "ETag",
//                        "v1",
//                        "Vary",
//                        "Accept-Charset",
//                        "Donut",
//                        "a",
//                    ),
//                body = "A",
//            ),
//        )
//        server.enqueue(
//            MockResponse(
//                headers = headersOf("Donut", "b"),
//                body = "B",
//            ),
//        )
//        client =
//            client
//                .newBuilder()
//                .cache(cache)
//                .build()
//        val request1SentAt = System.currentTimeMillis()
//        executeSynchronously("/", "Accept-Language", "fr-CA", "Accept-Charset", "UTF-8")
//            .assertCode(200)
//            .assertBody("A")
//        val request1ReceivedAt = System.currentTimeMillis()
//        assertThat(server.takeRequest().headers["If-None-Match"]).isNull()
//
//        // Different request, but Vary says it doesn't matter.
//        Thread.sleep(10) // Make sure the timestamps are unique.
//        val request2SentAt = System.currentTimeMillis()
//        val cacheMiss =
//            executeSynchronously(
//                "/",
//                "Accept-Language",
//                "en-US",
//                "Accept-Charset",
//                "UTF-8",
//            )
//        val request2ReceivedAt = System.currentTimeMillis()
//        assertThat(server.takeRequest().headers["If-None-Match"]).isEqualTo("v1")
//
//        // Check the user response. It has the application's original request.
//        cacheMiss
//            .assertCode(200)
//            .assertBody("B")
//            .assertHeader("Donut", "b")
//            .assertRequestUrl(server.url("/"))
//            .assertSentRequestAtMillis(request2SentAt, request2ReceivedAt)
//            .assertReceivedResponseAtMillis(request2SentAt, request2ReceivedAt)
//
//        // Check the cache response. Even though it's a miss, we used the cache.
//        cacheMiss
//            .cacheResponse()
//            .assertCode(200)
//            .assertHeader("Donut", "a")
//            .assertHeader("ETag", "v1")
//            .assertRequestUrl(server.url("/"))
//            .assertSentRequestAtMillis(request1SentAt, request1ReceivedAt)
//            .assertReceivedResponseAtMillis(request1SentAt, request1ReceivedAt)
//
//        // Check the network response. It has the network request, plus caching headers.
//        cacheMiss
//            .networkResponse()
//            .assertCode(200)
//            .assertHeader("Donut", "b")
//            .assertRequestHeader("If-None-Match", "v1") // If-None-Match in the validation request.
//            .assertRequestUrl(server.url("/"))
//            .assertSentRequestAtMillis(request2SentAt, request2ReceivedAt)
//            .assertReceivedResponseAtMillis(request2SentAt, request2ReceivedAt)
//    }
//
//    @Test
//    fun conditionalCacheMiss_Async() {
//        server.enqueue(
//            MockResponse(
//                body = "A",
//                headers = headersOf("ETag", "v1"),
//            ),
//        )
//        server.enqueue(MockResponse(body = "B"))
//        client =
//            client
//                .newBuilder()
//                .cache(cache)
//                .build()
//        val request1 = ClientRequest.get(server.url("/").toJayo())
//        client.newCall(request1).enqueue(callback)
//        callback.await(request1.url).assertCode(200).assertBody("A")
//        assertThat(server.takeRequest().headers["If-None-Match"]).isNull()
//        val request2 = ClientRequest.get(server.url("/").toJayo())
//        client.newCall(request2).enqueue(callback)
//        callback.await(request2.url).assertCode(200).assertBody("B")
//        assertThat(server.takeRequest().headers["If-None-Match"]).isEqualTo("v1")
//    }
//
////    @Test
//    fun onlyIfCachedReturns504WhenNotCached() {
//        executeSynchronously("/", "Cache-Control", "only-if-cached")
//            .assertCode(504)
//            .assertBody("")
//            .assertNoNetworkResponse()
//            .assertNoCacheResponse()
//    }
//
//    @Test
//    fun networkDropsOnConditionalGet() {
//        client =
//            client
//                .newBuilder()
//                .cache(cache)
//                .build()
//
//        // Seed the cache.
//        server.enqueue(
//            MockResponse(
//                headers = headersOf("ETag", "v1"),
//                body = "A",
//            ),
//        )
//        executeSynchronously("/")
//            .assertCode(200)
//            .assertBody("A")
//
//        // Attempt conditional cache validation and a DNS miss.
//        client =
//            client
//                .newBuilder()
//                .dns(FakeDns())
//                .build()
//        executeSynchronously("/").assertFailure(UnknownHostException::class.java)
//    }

    @Test
    fun redirect() {
        platform.assumeConscrypt() // whatever assertion on platform works, it is required for exchangeIndex check

        server.enqueue(
            MockResponse(
                code = 301,
                headers =
                    headersOf(
                        "Location",
                        "/b",
                        "Test",
                        "Redirect from /a to /b",
                    ),
                body = "/a has moved!",
            ),
        )
        server.enqueue(
            MockResponse(
                code = 302,
                headers =
                    headersOf(
                        "Location",
                        "/c",
                        "Test",
                        "Redirect from /b to /c",
                    ),
                body = "/b has moved!",
            ),
        )
        server.enqueue(MockResponse(body = "C"))
        executeSynchronously("/a")
            .assertCode(200)
            .assertBody("C")
            .priorResponse()
            .assertCode(302)
            .assertHeader("Test", "Redirect from /b to /c")
            .priorResponse()
            .assertCode(301)
            .assertHeader("Test", "Redirect from /a to /b")

        // New connection.
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
        // Connection reused.
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)
        // Connection reused again!
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(2)
    }

    @Test
    fun postRedirectsToGet() {
        server.enqueue(
            MockResponse(
                code = HttpURLConnection.HTTP_MOVED_TEMP,
                headers = headersOf("Location", "/page2"),
                body = "This page has moved!",
            ),
        )
        server.enqueue(MockResponse(body = "Page 2"))
        val response =
            client
                .newCall(
                    ClientRequest.builder()
                        .url(server.url("/page1").toJayo())
                        .post("Request Body".toRequestBody("text/plain".toMediaType()))
                ).execute()
        assertThat(response.body.string()).isEqualTo("Page 2")
        val page1 = server.takeRequest()
        assertThat(page1.requestLine).isEqualTo("POST /page1 HTTP/1.1")
        assertThat(page1.body?.utf8()).isEqualTo("Request Body")
        val page2 = server.takeRequest()
        assertThat(page2.requestLine).isEqualTo("GET /page2 HTTP/1.1")
    }

    @Test
    fun getClientRequestTimeout() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(408)
                .addHeader("Connection", "Close")
                .body("You took too long!")
                .onResponseEnd(ShutdownConnection)
                .build()
        )
        server.enqueue(MockResponse(body = "Body"))
        val request = ClientRequest.get(server.url("/").toJayo())
        val response = client.newCall(request).execute()
        assertThat(response.body.string()).isEqualTo("Body")
    }

    @Test
    fun getClientRequestTimeoutWithBackPressure() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(408)
                .addHeader("Connection", "Close")
                .addHeader("Retry-After", "1")
                .body("You took too long!")
                .onResponseEnd(ShutdownConnection)
                .build()
        )
        val request = ClientRequest.get(server.url("/").toJayo())
        val response = client.newCall(request).execute()
        assertThat(response.body.string()).isEqualTo("You took too long!")
    }

    @Test
    fun requestBodyRetransmittedOnClientRequestTimeout() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(408)
                .addHeader("Connection", "Close")
                .body("You took too long!")
                .onResponseEnd(ShutdownConnection)
                .build()
        )
        server.enqueue(MockResponse(body = "Body"))
        val request = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .post("Hello".toRequestBody("text/plain".toMediaType()))
        val response = client.newCall(request).execute()
        assertThat(response.body.string()).isEqualTo("Body")
        val request1 = server.takeRequest()
        assertThat(request1.body?.utf8()).isEqualTo("Hello")
        val request2 = server.takeRequest()
        assertThat(request2.body?.utf8()).isEqualTo("Hello")
    }

    @Test
    fun disableClientRequestTimeoutRetry() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(408)
                .addHeader("Connection", "Close")
                .body("You took too long!")
                .onResponseEnd(ShutdownConnection)
                .build()
        )
        client =
            client
                .newBuilder()
                .retryOnConnectionFailure(false)
                .build()
        val request = ClientRequest.get(server.url("/").toJayo())
        val response = client.newCall(request).execute()
        assertThat(response.status.code).isEqualTo(408)
        assertThat(response.body.string()).isEqualTo("You took too long!")
    }

    @Test
    fun maxClientRequestTimeoutRetries() {
        platform.assumeConscrypt() // whatever assertion on platform works, it is required for requestCount check

        server.enqueue(
            MockResponse
                .Builder()
                .code(408)
                .addHeader("Connection", "Close")
                .body("You took too long!")
                .onResponseEnd(ShutdownConnection)
                .build()
        )
        server.enqueue(
            MockResponse
                .Builder()
                .code(408)
                .addHeader("Connection", "Close")
                .body("You took too long!")
                .onResponseEnd(ShutdownConnection)
                .build()
        )
        val request = ClientRequest.get(server.url("/").toJayo())
        val response = client.newCall(request).execute()
        assertThat(response.status.code).isEqualTo(408)
        assertThat(response.body.string()).isEqualTo("You took too long!")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun maxUnavailableTimeoutRetries() {
        platform.assumeConscrypt() // whatever assertion on platform works, it is required for requestCount check

        server.enqueue(
            MockResponse
                .Builder()
                .code(503)
                .addHeader("Connection", "Close")
                .addHeader("Retry-After", "0")
                .body("You took too long!")
                .onResponseEnd(ShutdownConnection)
                .build()
        )
        server.enqueue(
            MockResponse
                .Builder()
                .code(503)
                .addHeader("Connection", "Close")
                .addHeader("Retry-After", "0")
                .body("You took too long!")
                .onResponseEnd(ShutdownConnection)
                .build()
        )
        val request = ClientRequest.get(server.url("/").toJayo())
        val response = client.newCall(request).execute()
        assertThat(response.status.code).isEqualTo(503)
        assertThat(response.body.string()).isEqualTo("You took too long!")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun retryOnUnavailableWith0RetryAfter() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(503)
                .addHeader("Connection", "Close")
                .addHeader("Retry-After", "0")
                .body("You took too long!")
                .onResponseEnd(ShutdownConnection)
                .build()
        )
        server.enqueue(MockResponse(body = "Body"))
        val request = ClientRequest.get(server.url("/").toJayo())
        val response = client.newCall(request).execute()
        assertThat(response.body.string()).isEqualTo("Body")
    }

    @Test
    fun canRetryNormalRequestBody() {
        platform.assumeConscrypt() // whatever assertion on platform works, it is required for requestCount check

        server.enqueue(
            MockResponse(
                code = 503,
                headers = headersOf("Retry-After", "0"),
                body = "please retry",
            ),
        )
        server.enqueue(
            MockResponse(body = "thank you for retrying"),
        )
        val request = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .post(
                object : ClientRequestBody() {
                    var attempt = 0

                    override fun contentType(): MediaType? = null

                    override fun writeTo(destination: Writer) {
                        destination.write("attempt " + attempt++)
                    }
                },
            )
        val response = client.newCall(request).execute()
        assertThat(response.status.code).isEqualTo(200)
        assertThat(response.body.string()).isEqualTo("thank you for retrying")
        assertThat(server.takeRequest().body?.utf8()).isEqualTo("attempt 0")
        assertThat(server.takeRequest().body?.utf8()).isEqualTo("attempt 1")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun cannotRetryOneShotRequestBody() {
        platform.assumeConscrypt() // whatever assertion on platform works, it is required for requestCount check

        server.enqueue(
            MockResponse(
                code = 503,
                headers = headersOf("Retry-After", "0"),
                body = "please retry",
            ),
        )
        server.enqueue(
            MockResponse(body = "thank you for retrying"),
        )
        val request = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .post(
                object : ClientRequestBody() {
                    var attempt = 0

                    override fun contentType(): MediaType? = null

                    override fun writeTo(destination: Writer) {
                        destination.write("attempt " + attempt++)
                    }

                    override fun isOneShot(): Boolean = true
                },
            )
        val response = client.newCall(request).execute()
        assertThat(response.status.code).isEqualTo(503)
        assertThat(response.body.string()).isEqualTo("please retry")
        assertThat(server.takeRequest().body?.utf8()).isEqualTo("attempt 0")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun propfindRedirectsToPropfindAndMaintainsRequestBody() {
        // given
        server.enqueue(
            MockResponse(
                code = HttpURLConnection.HTTP_MOVED_TEMP,
                headers = headersOf("Location", "/page2"),
                body = "This page has moved!",
            ),
        )
        server.enqueue(MockResponse(body = "Page 2"))

        // when
        val response =
            client
                .newCall(
                    ClientRequest.builder()
                        .url(server.url("/page1").toJayo())
                        .method(
                            "PROPFIND",
                            "Request Body".toRequestBody("text/plain".toMediaType())
                        )
                ).execute()

        // then
        assertThat(response.body.string()).isEqualTo("Page 2")
        val page1 = server.takeRequest()
        assertThat(page1.requestLine).isEqualTo("PROPFIND /page1 HTTP/1.1")
        assertThat(page1.body?.utf8()).isEqualTo("Request Body")
        val page2 = server.takeRequest()
        assertThat(page2.requestLine).isEqualTo("PROPFIND /page2 HTTP/1.1")
        assertThat(page2.body?.utf8()).isEqualTo("Request Body")
    }

    @Test
    fun responseCookies() {
        server.enqueue(
            MockResponse(
                headers =
                    headersOf(
                        "Set-Cookie",
                        "a=b; Expires=Thu, 01 Jan 1970 00:00:00 GMT",
                        "Set-Cookie",
                        "c=d; Expires=Fri, 02 Jan 1970 23:59:59 GMT; path=/bar; secure",
                    ),
            ),
        )
        val cookieJar = RecordingCookieJar()
        client =
            client
                .newBuilder()
                .cookieJar(cookieJar)
                .build()
        executeSynchronously("/").assertCode(200)
        val responseCookies = cookieJar.takeResponseCookies()
        assertThat(responseCookies.size).isEqualTo(2)
        assertThat(responseCookies[0].toString())
            .isEqualTo("a=b; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/")
        assertThat(responseCookies[1].toString())
            .isEqualTo("c=d; expires=Fri, 02 Jan 1970 23:59:59 GMT; path=/bar; secure")
    }

    @Test
    fun requestCookies() {
        server.enqueue(MockResponse())
        val cookieJar = RecordingCookieJar()
        cookieJar.enqueueRequestCookies(
            Cookie.builder()
                .name("a")
                .value("b")
                .domain(server.hostName)
                .build(),
            Cookie.builder()
                .name("c")
                .value("d")
                .domain(server.hostName)
                .build(),
        )
        client =
            client
                .newBuilder()
                .cookieJar(cookieJar)
                .build()
        executeSynchronously("/").assertCode(200)
        val recordedRequest = server.takeRequest()
        assertThat(recordedRequest.headers["Cookie"]).isEqualTo("a=b; c=d")
    }

    @Test
    fun redirectsDoNotIncludeTooManyCookies() {
        server2.enqueue(MockResponse(body = "Page 2"))
        server.enqueue(
            MockResponse(
                code = HttpURLConnection.HTTP_MOVED_TEMP,
                headers = headersOf("Location", server2.url("/").toString()),
            ),
        )
        val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER)
        val cookie = HttpCookie("c", "cookie")
        cookie.domain = server.hostName
        cookie.path = "/"
        val portList = server.port.toString()
        cookie.portlist = portList
        cookieManager.cookieStore.add(server.url("/").toUri(), cookie)
        client =
            client
                .newBuilder()
                .cookieJar(javaNetCookieJar(cookieManager))
                .build()
        val response = client
            .newCall(ClientRequest.get(server.url("/page1").toJayo()))
            .execute()
        assertThat(response.body.string()).isEqualTo("Page 2")
        val request1 = server.takeRequest()
        assertThat(request1.headers["Cookie"]).isEqualTo("c=cookie")
        val request2 = server2.takeRequest()
        assertThat(request2.headers["Cookie"]).isNull()
    }

    @Test
    fun redirectsDoNotIncludeTooManyAuthHeaders() {
        server2.enqueue(MockResponse(body = "Page 2"))
        server.enqueue(MockResponse(code = 401))
        server.enqueue(
            MockResponse(
                code = 302,
                headers = headersOf("Location", server2.url("/b").toString()),
            ),
        )
        client =
            client
                .newBuilder()
                .authenticator(RecordingJayoAuthenticator(basic("pull-vert", "secret"), null))
                .build()
        val request = ClientRequest.builder().url(server.url("/a").toJayo()).get()
        val response = client.newCall(request).execute()
        assertThat(response.body.string()).isEqualTo("Page 2")
        val redirectRequest = server2.takeRequest()
        assertThat(redirectRequest.headers["Authorization"]).isNull()
        assertThat(redirectRequest.url.encodedPath).isEqualTo("/b")
    }

    @Test
    fun redirect_Async() {
        platform.assumeConscrypt() // whatever assertion on platform works, it is required for exchangeIndex check

        server.enqueue(
            MockResponse(
                code = 301,
                headers =
                    headersOf(
                        "Location",
                        "/b",
                        "Test",
                        "Redirect from /a to /b",
                    ),
                body = "/a has moved!",
            ),
        )
        server.enqueue(
            MockResponse(
                code = 302,
                headers =
                    headersOf(
                        "Location",
                        "/c",
                        "Test",
                        "Redirect from /b to /c",
                    ),
                body = "/b has moved!",
            ),
        )
        server.enqueue(MockResponse(body = "C"))
        val request = ClientRequest.builder().url(server.url("/a").toJayo()).get()
        client.newCall(request).enqueue(callback)
        callback
            .await(server.url("/a").toJayo())
            .assertCode(200)
            .assertBody("C")
            .priorResponse()
            .assertCode(302)
            .assertHeader("Test", "Redirect from /b to /c")
            .priorResponse()
            .assertCode(301)
            .assertHeader("Test", "Redirect from /a to /b")

        // New connection.
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
        // Connection reused.
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)
        // Connection reused again!
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(2)
    }

    @Test
    fun follow20Redirects() {
        for (i in 0..19) {
            server.enqueue(
                MockResponse(
                    code = 301,
                    headers = headersOf("Location", "/${i + 1}"),
                    body = "Redirecting to /" + (i + 1),
                ),
            )
        }
        server.enqueue(MockResponse(body = "Success!"))
        executeSynchronously("/0")
            .assertCode(200)
            .assertBody("Success!")
    }

    @Test
    fun follow20Redirects_Async() {
        for (i in 0..19) {
            server.enqueue(
                MockResponse(
                    code = 301,
                    headers = headersOf("Location", "/" + (i + 1)),
                    body = "Redirecting to /" + (i + 1),
                ),
            )
        }
        server.enqueue(MockResponse(body = "Success!"))
        val request = ClientRequest.builder().url(server.url("/0").toJayo()).get()
        client.newCall(request).enqueue(callback)
        callback
            .await(server.url("/0").toJayo())
            .assertCode(200)
            .assertBody("Success!")
    }

    @Test
    fun doesNotFollow21Redirects() {
        for (i in 0..20) {
            server.enqueue(
                MockResponse(
                    code = 301,
                    headers = headersOf("Location", "/${i + 1}"),
                    body = "Redirecting to /${i + 1}",
                ),
            )
        }
        assertFailsWith<JayoProtocolException> {
            client.newCall(ClientRequest.builder().url(server.url("/0").toJayo()).get()).execute()
        }.also { expected ->
            assertThat(expected.message).isEqualTo("Too many follow-up requests: 21")
        }
    }

    @Test
    fun doesNotFollow21Redirects_Async() {
        for (i in 0..20) {
            server.enqueue(
                MockResponse(
                    code = 301,
                    headers = headersOf("Location", "/${i + 1}"),
                    body = "Redirecting to /${i + 1}",
                ),
            )
        }
        val request = ClientRequest.builder().url(server.url("/0").toJayo()).get()
        client.newCall(request).enqueue(callback)
        callback.await(server.url("/0").toJayo()).assertFailure("Too many follow-up requests: 21")
    }

    @Test
    fun http204WithBodyDisallowed() {
        server.enqueue(
            MockResponse(
                code = 204,
                body = "I'm not even supposed to be here today.",
            ),
        )
        executeSynchronously("/")
            .assertFailure("HTTP 204 had non-zero Content-Length: 39")
    }

    @Test
    fun http205WithBodyDisallowed() {
        server.enqueue(
            MockResponse(
                code = 205,
                body = "I'm not even supposed to be here today.",
            ),
        )
        executeSynchronously("/")
            .assertFailure("HTTP 205 had non-zero Content-Length: 39")
    }

    @Test
    fun httpWithExcessiveStatusLine() {
        val longLine = "HTTP/1.1 200 " + "O".repeat(256 * 1024) + "K"
        server.protocols = listOf(okhttp3.Protocol.HTTP_1_1)
        server.enqueue(
            MockResponse
                .Builder()
                .status(longLine)
                .body("I'm not even supposed to be here today.")
                .build(),
        )
        executeSynchronously("/")
            .assertFailureMatches(".*unexpected end of stream on ${server.url("/").redact()}")
    }

    @Test
    fun httpWithExcessiveHeaders() {
        server.protocols = listOf(okhttp3.Protocol.HTTP_1_1)
        server.enqueue(
            MockResponse
                .Builder()
                .addHeader("Set-Cookie", "a=${"A".repeat(255 * 1024)}")
                .addHeader("Set-Cookie", "b=${"B".repeat(1 * 1024)}")
                .body("I'm not even supposed to be here today.")
                .build(),
        )
        executeSynchronously("/")
            .assertFailureMatches(".*unexpected end of stream on ${server.url("/").redact()}")
    }

    @Test
    fun canceledBeforeExecute() {
        val call = client.newCall(ClientRequest.get(server.url("/a").toJayo()))
        call.cancel()
        assertFailsWith<JayoException> {
            call.execute()
        }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun cancelDuringHttpConnect() {
        cancelDuringConnect(server, "http")
    }

    @Test
    fun cancelDuringHttpsConnect() {
        cancelDuringConnect(server, "https")
    }

    /** Cancel a call that's waiting for connect to complete.  */
    private fun cancelDuringConnect(server: MockWebServer, scheme: String?) {
        server.enqueue(MockResponse.Builder().onRequestStart(Stall).build())
        val cancelDelayMillis = 300L
        val call =
            client.newCall(
                ClientRequest.builder()
                    .url(
                        server
                            .url("/")
                            .newBuilder()
                            .scheme(scheme!!)
                            .build().toJayo()
                    ).get()
            )
        cancelLater(call, cancelDelayMillis)
        val startNanos = System.nanoTime()
        assertFailsWith<JayoException> {
            call.execute()
        }
        val elapsedNanos = System.nanoTime() - startNanos
        assertThat(TimeUnit.NANOSECONDS.toMillis(elapsedNanos).toFloat())
            .isCloseTo(cancelDelayMillis.toFloat(), Offset.offset(100f))
    }

    @Test
    fun cancelImmediatelyAfterEnqueue() {
        server.enqueue(MockResponse())
        val latch = CountDownLatch(1)
        client =
            client
                .newBuilder()
                .addNetworkInterceptor { chain: Interceptor.Chain ->
                    try {
                        latch.await()
                    } catch (e: InterruptedException) {
                        throw kotlin.AssertionError(e)
                    }
                    chain.proceed(chain.request())
                }.build()
        val call = client.newCall(ClientRequest.get(server.url("/a").toJayo()))
        call.enqueue(callback)
        call.cancel()
        latch.countDown()
        callback.await(server.url("/a").toJayo())
            .assertFailure("Canceled", "Socket closed", "Socket is closed")
    }

    @Test
    fun cancelAll() {
        val call = client.newCall(ClientRequest.get(server.url("/").toJayo()))
        call.enqueue(callback)
        client.dispatcher.cancelAll()
        callback.await(server.url("/").toJayo())
            .assertFailure("Canceled", "Socket closed", "Socket is closed")
    }

    @Test
    fun cancelWhileRequestHeadersAreSent() {
        server.enqueue(MockResponse(body = "A"))
        val listener: EventListener =
            object : EventListener() {
                override fun requestHeadersStart(call: Call) {
                    try {
                        // Cancel call from another thread to avoid reentrance.
                        cancelLater(call, 0).join()
                    } catch (_: InterruptedException) {
                        throw kotlin.AssertionError()
                    }
                }
            }
        client = client.newBuilder().eventListener(listener).build()
        val call = client.newCall(ClientRequest.get(server.url("/a").toJayo()))
        assertFailsWith<JayoException> {
            call.execute()
        }
    }

    @Test
    fun cancelWhileRequestHeadersAreSent_HTTP_2() {
        enableProtocol(Protocol.HTTP_2)
        cancelWhileRequestHeadersAreSent()
    }

    @Test
    fun cancelBeforeBodyIsRead() {
        platform.assumeConscrypt() // whatever assertion on platform works, it is required for exchangeIndex check

        server.enqueue(
            MockResponse
                .Builder()
                .body("def")
                .throttleBody(1, 750, TimeUnit.MILLISECONDS)
                .build(),
        )
        val call = client.newCall(ClientRequest.get(server.url("/a").toJayo()))
        val executor = Executors.newSingleThreadExecutor()
        val result = executor.submit<ClientResponse?> { call.execute() }
        Thread.sleep(200) // wait for it to go in flight.
        call.cancel()
        assertFailsWith<JayoException> {
            result.get()!!.body.bytes()
        }
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun cancelInFlightBeforeResponseReadThrowsIOE() {
        val request = ClientRequest.get(server.url("/a").toJayo())
        val call = client.newCall(request)
        server.dispatcher =
            object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    call.cancel()
                    return MockResponse(body = "A")
                }
            }
        assertFailsWith<JayoException> {
            call.execute()
        }
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/a")
    }

    @Test
    fun cancelInFlightBeforeResponseReadThrowsIOE_HTTPS() {
        enableTls()
        cancelInFlightBeforeResponseReadThrowsIOE()
    }

    @Test
    fun cancelInFlightBeforeResponseReadThrowsIOE_HTTP_2() {
        enableProtocol(Protocol.HTTP_2)
        cancelInFlightBeforeResponseReadThrowsIOE()
    }

    /**
     * This test puts a request in front of one that is to be canceled so that it is canceled before I/O takes place.
     */
    @Test
    fun canceledBeforeIOSignalsOnFailure() {
        // Force requests to be executed serially.
        val dispatcher = Dispatcher.newBuilder()
            .executorService(client.dispatcher.executorService)
            .maxRequests(1)
            .build()
        client =
            client
                .newBuilder()
                .dispatcher(dispatcher)
                .build()
        val requestA = ClientRequest.get(server.url("/a").toJayo())
        val requestB = ClientRequest.get(server.url("/b").toJayo())
        val callA = client.newCall(requestA)
        val callB = client.newCall(requestB)
        server.dispatcher =
            object : mockwebserver3.Dispatcher() {
                var nextResponse = 'A'

                override fun dispatch(request: RecordedRequest): MockResponse {
                    callB.cancel()
                    return MockResponse(body = (nextResponse++).toString())
                }
            }
        callA.enqueue(callback)
        callB.enqueue(callback)
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/a")
        callback.await(requestA.url).assertBody("A")
        // At this point we know the callback is ready and that it will receive a cancel failure.
        callback.await(requestB.url).assertFailure("Canceled", "Socket closed")
    }

    @Test
    fun canceledBeforeIOSignalsOnFailure_HTTPS() {
        enableTls()
        canceledBeforeIOSignalsOnFailure()
    }

    @Test
    fun canceledBeforeIOSignalsOnFailure_HTTP_2() {
        enableProtocol(Protocol.HTTP_2)
        canceledBeforeIOSignalsOnFailure()
    }

    @Test
    fun canceledBeforeResponseReadSignalsOnFailure() {
        val requestA = ClientRequest.get(server.url("/a").toJayo())
        val call = client.newCall(requestA)
        server.dispatcher =
            object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    call.cancel()
                    return MockResponse(body = "A")
                }
            }
        call.enqueue(callback)
        assertThat(server.takeRequest().url.encodedPath).isEqualTo("/a")
        callback.await(requestA.url).assertFailure(JayoException::class.java)
    }

    @Test
    fun canceledBeforeResponseReadSignalsOnFailure_HTTPS() {
        enableTls()
        canceledBeforeResponseReadSignalsOnFailure()
    }

    @Test
    fun canceledBeforeResponseReadSignalsOnFailure_HTTP_2() {
        enableProtocol(Protocol.HTTP_2)
        canceledBeforeResponseReadSignalsOnFailure()
    }

    /** There's a race condition where the cancel may apply after the stream has already been processed. */
    @Test
    fun canceledAfterResponseIsDeliveredBreaksStreamButSignalsOnce() {
        server.enqueue(MockResponse(body = "A"))
        val latch = CountDownLatch(1)
        val bodyRef = AtomicReference<String?>()
        val failureRef = AtomicBoolean()
        val request = ClientRequest.get(server.url("/a").toJayo())
        val call = client.newCall(request)
        call.enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    je: JayoException,
                ) {
                    failureRef.set(true)
                    latch.countDown()
                }

                override fun onResponse(
                    call: Call,
                    response: ClientResponse,
                ) {
                    call.cancel()
                    try {
                        bodyRef.set(response.body.string())
                    } catch (je: JayoException) {
                        // It is ok if this broke the stream.
                        bodyRef.set("A")
                        throw je // We expect to not loop into onFailure in this case.
                    } finally {
                        latch.countDown()
                    }
                }
            },
        )
        latch.await()
        assertThat(bodyRef.get()).isEqualTo("A")
        assertThat(failureRef.get()).isFalse()
    }

    @Test
    fun canceledAfterResponseIsDeliveredBreaksStreamButSignalsOnce_HTTPS() {
        enableTls()
        canceledAfterResponseIsDeliveredBreaksStreamButSignalsOnce()
    }

    @Test
    fun canceledAfterResponseIsDeliveredBreaksStreamButSignalsOnce_HTTP_2() {
        enableProtocol(Protocol.HTTP_2)
        canceledAfterResponseIsDeliveredBreaksStreamButSignalsOnce()
    }

    @Test
    fun cancelWithInterceptor() {
        platform.assumeConscrypt() // whatever assertion on platform works, it is required for exchangeIndex check

        client =
            client
                .newBuilder()
                .addInterceptor { chain: Interceptor.Chain ->
                    chain.proceed(chain.request())
                    throw kotlin.AssertionError() // We expect an exception.
                }.build()
        val call = client.newCall(ClientRequest.get(server.url("/a").toJayo()))
        call.cancel()
        assertFailsWith<JayoException> {
            call.execute()
        }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun gzip() {
        val gzippedBody = gzip("abcabcabc")
        val bodySize = java.lang.Long.toString(gzippedBody.size)

        server.enqueue(
            MockResponse
                .Builder()
                .body(gzippedBody)
                .addHeader("Content-Encoding: gzip")
                .build(),
        )

        // Confirm that the user request doesn't have Accept-Encoding, and the user
        // response doesn't have a Content-Encoding or Content-Length.
        val userResponse = executeSynchronously("/")
        userResponse
            .assertCode(200)
            .assertRequestHeader("Accept-Encoding")
            .assertHeader("Content-Encoding")
            .assertHeader("Content-Length")
            .assertBody("abcabcabc")

        // But the network request doesn't lie. OkHttp used gzip for this call.
        userResponse
            .networkResponse()
            .assertHeader("Content-Encoding", "gzip")
            .assertHeader("Content-Length", bodySize)
            .assertRequestHeader("Accept-Encoding", "gzip")
    }

    /** https://github.com/square/okhttp/issues/1927  */
    @Test
    fun gzipResponseAfterAuthenticationChallenge() {
        server.enqueue(MockResponse(code = 401))
        server.enqueue(
            MockResponse
                .Builder()
                .body(gzip("abcabcabc"))
                .addHeader("Content-Encoding: gzip")
                .build(),
        )
        client =
            client
                .newBuilder()
                .authenticator(RecordingJayoAuthenticator("password", null))
                .build()
        executeSynchronously("/").assertBody("abcabcabc")
    }

    @Test
    fun rangeHeaderPreventsAutomaticGzip() {
        val gzippedBody = gzip("abcabcabc")

        // Enqueue a gzipped response. Our request isn't expecting it, but that's okay.
        server.enqueue(
            MockResponse
                .Builder()
                .code(HttpURLConnection.HTTP_PARTIAL)
                .body(gzippedBody)
                .addHeader("Content-Encoding: gzip")
                .addHeader("Content-Range: bytes 0-" + (gzippedBody.size - 1))
                .build(),
        )

        // Make a range request.
        val request = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .header("Range", "bytes=0-")
            .get()
        val call = client.newCall(request)

        // The response is not decompressed.
        val response = call.execute()
        assertThat(response.header("Content-Encoding")).isEqualTo("gzip")
        assertThat(response.body.reader().readByteArray()).isEqualTo(gzippedBody.readByteArray())

        // The request did not offer gzip support.
        val recordedRequest = server.takeRequest()
        assertThat(recordedRequest.headers["Accept-Encoding"]).isNull()
    }

    @Test
    fun asyncResponseCanBeConsumedLater() {
        platform.assumeConscrypt() // whatever assertion on platform works, it is required for exchangeIndex check

        server.enqueue(MockResponse(body = "abc"))
        server.enqueue(MockResponse(body = "def"))
        val request = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .header("User-Agent", "SyncApiTest")
            .get()
        val responseRef: BlockingQueue<ClientResponse> = SynchronousQueue()
        client.newCall(request).enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    je: JayoException,
                ): Unit = throw kotlin.AssertionError()

                override fun onResponse(
                    call: Call,
                    response: ClientResponse,
                ) {
                    try {
                        responseRef.put(response)
                    } catch (_: InterruptedException) {
                        throw kotlin.AssertionError()
                    }
                }
            },
        )
        val response = responseRef.take()
        assertThat(response.status.code).isEqualTo(200)
        assertThat(response.body.string()).isEqualTo("abc")

        // Make another request just to confirm that that connection can be reused...
        executeSynchronously("/").assertBody("def")
        // New connection.
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
        // Connection reused.
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)

        // ... even before we close the response body!
        response.body.close()
    }

    @Test
    fun userAgentIsIncludedByDefault() {
        server.enqueue(MockResponse())
        executeSynchronously("/")
        val recordedRequest = server.takeRequest()
        assertThat(recordedRequest.headers["User-Agent"]!!).isEqualTo(USER_AGENT)
    }

    @Test
    fun setFollowRedirectsFalse() {
        platform.assumeConscrypt() // whatever assertion on platform works

        server.enqueue(
            MockResponse(
                code = 302,
                headers = headersOf("Location", "/b"),
                body = "A",
            ),
        )
        server.enqueue(MockResponse(body = "B"))
        client =
            client
                .newBuilder()
                .followRedirects(false)
                .build()
        executeSynchronously("/a")
            .assertBody("A")
            .assertCode(302)
    }

    @Test
    fun expect100ContinueNonEmptyRequestBody() {
        server.enqueue(
            MockResponse
                .Builder()
                .add100Continue()
                .build(),
        )
        val request = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .header("Expect", "100-continue")
            .post("abc".toRequestBody("text/plain".toMediaType()))
        executeSynchronously(request)
            .assertCode(200)
            .assertSuccessful()
        assertThat(server.takeRequest().body?.utf8()).isEqualTo("abc")
    }

    @Test
    fun expect100ContinueEmptyRequestBody() {
        server.enqueue(MockResponse())
        val request = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .header("Expect", "100-continue")
            .post("".toRequestBody("text/plain".toMediaType()))
        executeSynchronously(request)
            .assertCode(200)
            .assertSuccessful()
    }

    @Test
    fun expect100ContinueEmptyRequestBody_HTTP2() {
        enableProtocol(Protocol.HTTP_2)
        expect100ContinueEmptyRequestBody()
    }

    @Test
    fun expect100ContinueTimesOutWithoutContinue() {
        server.enqueue(MockResponse.Builder().onResponseStart(Stall).build())
        client =
            client
                .newBuilder()
                .readTimeout(Duration.ofMillis(500))
                .build()
        val request = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .header("Expect", "100-continue")
            .post("abc".toRequestBody("text/plain".toMediaType()))
        val call = client.newCall(request)
        assertFailsWith<JayoTimeoutException> {
            call.execute()
        }
        val recordedRequest = server.takeRequest()
        assertThat(recordedRequest.body).isIn(null, ByteString.EMPTY)
    }

    @Test
    fun expect100ContinueTimesOutWithoutContinue_HTTP2() {
        enableProtocol(Protocol.HTTP_2)
        expect100ContinueTimesOutWithoutContinue()
    }

    @Test
    fun serverRespondsWithUnsolicited100Continue() {
        server.enqueue(
            MockResponse
                .Builder()
                .add100Continue()
                .build(),
        )
        val request = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .post("abc".toRequestBody("text/plain".toMediaType()))
        executeSynchronously(request)
            .assertCode(200)
            .assertSuccessful()
        val recordedRequest = server.takeRequest()
        assertThat(recordedRequest.body?.utf8()).isEqualTo("abc")
    }

    @Test
    fun serverRespondsWithUnsolicited100Continue_HTTP2() {
        enableProtocol(Protocol.HTTP_2)
        serverRespondsWithUnsolicited100Continue()
    }

    @Test
    fun serverRespondsWithEarlyHints() {
        server.enqueue(
            MockResponse
                .Builder()
                .addInformationalResponse(
                    MockResponse(
                        code = HTTP_EARLY_HINTS,
                        headers = headersOf("Link", "</style.css>; rel=preload; as=style"),
                    ),
                ).build(),
        )
        val request = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .post("abc".toRequestBody("text/plain".toMediaType()))
        executeSynchronously(request)
            .assertCode(200)
            .assertSuccessful()
        val recordedRequest = server.takeRequest()
        assertThat(recordedRequest.body?.utf8()).isEqualTo("abc")
        assertThat(recordedRequest.headers["Link"]).isNull()
    }

    @Test
    fun serverRespondsWithEarlyHintsHttp2() {
        enableProtocol(Protocol.HTTP_2)
        serverRespondsWithEarlyHints()
    }

    @Test
    fun serverReturnsMultiple100Continues() {
        server.enqueue(
            MockResponse
                .Builder()
                .add100Continue()
                .add100Continue()
                .add100Continue()
                .build(),
        )

        val request = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .post("abc".toRequestBody("text/plain".toMediaType()))
        executeSynchronously(request).assertCode(200).assertSuccessful()
        val recordedRequest = server.takeRequest()
        assertThat(recordedRequest.body?.utf8()).isEqualTo("abc")
    }

    @Test
    fun serverReturnsMultiple100ContinuesHttp2() {
        enableProtocol(Protocol.HTTP_2)
        serverReturnsMultiple100Continues()
    }

    @Test
    fun serverRespondsWithProcessing() {
        server.enqueue(
            MockResponse
                .Builder()
                .addInformationalResponse(
                    MockResponse(
                        code = HTTP_PROCESSING,
                    ),
                ).build(),
        )
        val request = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .post("abc".toRequestBody("text/plain".toMediaType()))
        executeSynchronously(request)
            .assertCode(200)
            .assertSuccessful()
        val recordedRequest = server.takeRequest()
        assertThat(recordedRequest.body?.utf8()).isEqualTo("abc")
    }

    @Test
    fun serverRespondsWithProcessingHttp2() {
        enableProtocol(Protocol.HTTP_2)
        serverRespondsWithProcessing()
    }

    @Test
    fun serverRespondsWithProcessingMultiple() {
        server.enqueue(
            MockResponse
                .Builder()
                .apply {
                    repeat(10) {
                        addInformationalResponse(
                            MockResponse(
                                code = HTTP_PROCESSING,
                            ),
                        )
                    }
                }.build(),
        )
        val request = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .post("abc".toRequestBody("text/plain".toMediaType()))
        executeSynchronously(request)
            .assertCode(200)
            .assertSuccessful()
        val recordedRequest = server.takeRequest()
        assertThat(recordedRequest.body?.utf8()).isEqualTo("abc")
    }

    @Test
    fun serverRespondsWith100ContinueOnly() {
        client =
            client
                .newBuilder()
                .readTimeout(Duration.ofSeconds(1))
                .build()
        server.enqueue(MockResponse(code = 100))
        val request = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .post("abc".toRequestBody("text/plain".toMediaType()))
        val call = client.newCall(request)
        assertFailsWith<JayoTimeoutException> {
            call.execute()
        }
        val recordedRequest = server.takeRequest()
        assertThat(recordedRequest.body?.utf8()).isEqualTo("abc")
    }

    @Test
    fun serverRespondsWith100ContinueOnly_HTTP2() {
        enableProtocol(Protocol.HTTP_2)
        serverRespondsWith100ContinueOnly()
    }

    @Test
    fun successfulExpectContinuePermitsConnectionReuse() {
        platform.assumeConscrypt() // whatever assertion on platform works, it is required for exchangeIndex check

        server.enqueue(
            MockResponse
                .Builder()
                .add100Continue()
                .build(),
        )
        server.enqueue(MockResponse())
        executeSynchronously(
            ClientRequest.builder()
                .url(server.url("/").toJayo())
                .header("Expect", "100-continue")
                .post("abc".toRequestBody("text/plain".toMediaType()))
        )
        executeSynchronously(ClientRequest.get(server.url("/").toJayo()))
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)
    }

    @Test
    fun successfulExpectContinuePermitsConnectionReuseWithHttp2() {
        enableProtocol(Protocol.HTTP_2)
        successfulExpectContinuePermitsConnectionReuse()
    }

    @Test // very slow
    fun unsuccessfulExpectContinuePreventsConnectionReuse() {
        platform.assumeConscrypt() // whatever assertion on platform works, it is required for exchangeIndex check

        server.enqueue(MockResponse())
        server.enqueue(MockResponse())
        executeSynchronously(
            ClientRequest.builder()
                .url(server.url("/").toJayo())
                .header("Expect", "100-continue")
                .post("abc".toRequestBody("text/plain".toMediaType()))
        )
        executeSynchronously(ClientRequest.get(server.url("/").toJayo()))
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
    }

    @Disabled // todo HTTP2
    @Test
    fun unsuccessfulExpectContinuePermitsConnectionReuseWithHttp2() {
        enableProtocol(Protocol.HTTP_2)
        server.enqueue(MockResponse())
        server.enqueue(MockResponse())
        executeSynchronously(
            ClientRequest.builder()
                .url(server.url("/").toJayo())
                .header("Expect", "100-continue")
                .post("abc".toRequestBody("text/plain".toMediaType()))
        )
        executeSynchronously(ClientRequest.get(server.url("/").toJayo()))
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)
    }

    /** We forbid non-ASCII characters in outgoing request headers, but accept UTF-8.  */
    @Test
    fun responseHeaderParsingIsLenient() {
        val headersBuilder = RealHeaders.Builder()
        headersBuilder.add("Content-Length", "0")
        headersBuilder.addLenient("a\tb: c\u007fd")
        val headers = headersBuilder.build()
        server.enqueue(MockResponse(headers = headers.toOkhttp()))
        executeSynchronously("/")
            .assertHeader("a\tb", "c\u007fd")
    }

    @Test
    fun customDns() {
        // Configure a DNS that returns our local MockWebServer for android.com.
        val dns = FakeDns()
        dns["android.com"] = Dns.SYSTEM.lookup(server.url("/").host)
        client =
            client
                .newBuilder()
                .dns(dns)
                .build()
        server.enqueue(MockResponse())
        val request = ClientRequest.get(
            server
                .url("/")
                .newBuilder()
                .host("android.com")
                .build()
                .toJayo(),
        )
        executeSynchronously(request).assertCode(200)
        dns.assertRequests("android.com")
    }

    @Disabled // fixme throws OutOfMemoryError: Java heap space
    @Test
    fun dnsReturnsZeroIpAddresses() {
        // Configure a DNS that returns our local MockWebServer for android.com.
        val dns = FakeDns()
        val ipAddresses = mutableListOf<InetAddress>()
        dns["android.com"] = ipAddresses
        client =
            client
                .newBuilder()
                .dns(dns)
                .build()
        server.enqueue(MockResponse())
        val request = ClientRequest.get(
            server
                .url("/")
                .newBuilder()
                .host("android.com")
                .build()
                .toJayo(),
        )
        executeSynchronously(request).assertFailure("$dns returned no addresses for android.com")
        dns.assertRequests("android.com")
    }

    @Disabled // todo HTTP2
    /** Failed HTTP/2 calls could break the entire connection.  */
    @Test
    fun failingCallsDoNotInterfereWithConnection() {
        //platform.assumeConscrypt() // whatever assertion on platform works, it is required for eventSequence check

        enableProtocol(Protocol.HTTP_2)
        server.enqueue(MockResponse(body = "Response 1"))
        server.enqueue(MockResponse(body = "Response 2"))
        val requestBody =
            object : ClientRequestBody() {
                override fun contentType(): MediaType? = null

                override fun writeTo(destination: Writer) {
                    destination.write("abc")
                    destination.flush()
                    makeFailingCall()
                    destination.write("def")
                    destination.flush()
                }
            }
        val call =
            client.newCall(
                ClientRequest.builder()
                    .url(server.url("/").toJayo())
                    .post(requestBody)
            )
        call.execute().use { response ->
            assertThat(response.status.code).isEqualTo(200)
            assertThat(response.body.string()).isNotEmpty()
        }
        val connectCount =
            listener.eventSequence
                .stream()
                .filter { event: CallEvent? -> event is ConnectStart }
                .count()
        assertThat(connectCount).isEqualTo(1)
    }

    /** Test which headers are sent unencrypted to the HTTP proxy.  */
    @Test
    fun proxyConnectOmitsApplicationHeaders() {
        val handshakeCertificates = JayoTlsUtils.localhost()
        server.useHttps(handshakeCertificates.sslSocketFactory())
        server.enqueue(
            MockResponse
                .Builder()
                .inTunnel()
                .build(),
        )
        server.enqueue(
            MockResponse(body = "encrypted response from the origin server"),
        )
        val hostnameVerifier = RecordingHostnameVerifier()
        client =
            client
                .newBuilder()
                .tlsClientBuilder(ClientTlsEndpoint.builder(handshakeCertificates))
                .proxies(server.proxyAddress.toJayo())
                .hostnameVerifier(hostnameVerifier)
                .build()
        val request = ClientRequest.builder()
            .url("https://android.com/foo")
            .header("Private", "Secret")
            .header("User-Agent", "App 1.0")
            .get()
        val response = client.newCall(request).execute()
        assertThat(response.body.string()).isEqualTo(
            "encrypted response from the origin server",
        )
        val connect = server.takeRequest()
        assertThat(connect.headers["Private"]).isNull()
        assertThat(connect.headers["User-Agent"]).isEqualTo(USER_AGENT)
        assertThat(connect.headers["Proxy-Connection"]).isEqualTo("Keep-Alive")
        assertThat(connect.headers["Host"]).isEqualTo("android.com:443")
        val get = server.takeRequest()
        assertThat(get.headers["Private"]).isEqualTo("Secret")
        assertThat(get.headers["User-Agent"]).isEqualTo("App 1.0")
        assertThat(hostnameVerifier.calls).containsExactly("verify android.com")
    }

    @Test
    fun proxyUpgradeFailsWithTruncatedResponse() {
        val handshakeCertificates = JayoTlsUtils.localhost()
        server.enqueue(
            MockResponse
                .Builder()
                .body("abc")
                .setHeader("Content-Length", "5")
                .onResponseEnd(ShutdownConnection)
                .build(),
        )
        val hostnameVerifier = RecordingHostnameVerifier()
        client =
            client
                .newBuilder()
                .tlsClientBuilder(ClientTlsEndpoint.builder(handshakeCertificates))
                .proxies(server.proxyAddress.toJayo())
                .hostnameVerifier(hostnameVerifier)
                .build()
        val request = ClientRequest.get("https://android.com/foo".toHttpUrl())
        assertFailsWith<JayoException> {
            client.newCall(request).execute()
        }.also { expected ->
            assertThat(expected).hasMessage("unexpected end of stream")
        }
    }

    /** Respond to a proxy authorization challenge.  */
    @Test
    fun proxyAuthenticateOnConnect() {
        val handshakeCertificates = JayoTlsUtils.localhost()
        server.useHttps(handshakeCertificates.sslSocketFactory())
        server.enqueue(
            MockResponse
                .Builder()
                .code(407)
                .headers(headersOf("Proxy-Authenticate", "Basic realm=\"localhost\""))
                .inTunnel()
                .build(),
        )
        server.enqueue(
            MockResponse
                .Builder()
                .inTunnel()
                .build(),
        )
        server.enqueue(
            MockResponse(body = "response body"),
        )
        client =
            client
                .newBuilder()
                .tlsClientBuilder(ClientTlsEndpoint.builder(handshakeCertificates))
                .proxies(server.proxyAddress.toJayo())
                .proxyAuthenticator(RecordingJayoAuthenticator("password", "Basic"))
                .hostnameVerifier(RecordingHostnameVerifier())
                .build()
        val request = ClientRequest.get("https://android.com/foo".toHttpUrl())
        val response = client.newCall(request).execute()
        assertThat(response.body.string()).isEqualTo("response body")
        val connect1 = server.takeRequest()
        assertThat(connect1.requestLine).isEqualTo("CONNECT android.com:443 HTTP/1.1")
        assertThat(connect1.headers["Proxy-Authorization"]).isNull()
        val connect2 = server.takeRequest()
        assertThat(connect2.requestLine).isEqualTo("CONNECT android.com:443 HTTP/1.1")
        assertThat(connect2.headers["Proxy-Authorization"]).isEqualTo("password")
        val get = server.takeRequest()
        assertThat(get.requestLine).isEqualTo("GET /foo HTTP/1.1")
        assertThat(get.headers["Proxy-Authorization"]).isNull()
    }

    /** Confirm that the proxy authenticator works for unencrypted HTTP proxies.  */
    @Test
    fun httpProxyAuthenticate() {
        server.enqueue(
            MockResponse(
                code = 407,
                headers = headersOf("Proxy-Authenticate", "Basic realm=\"localhost\""),
            ),
        )
        server.enqueue(
            MockResponse(body = "response body"),
        )
        client =
            client
                .newBuilder()
                .proxies(server.proxyAddress.toJayo())
                .proxyAuthenticator(RecordingJayoAuthenticator("password", "Basic"))
                .build()
        val request = ClientRequest.get("http://android.com/foo".toHttpUrl())
        val response = client.newCall(request).execute()
        assertThat(response.body.string()).isEqualTo("response body")
        val get1 = server.takeRequest()
        assertThat(get1.requestLine).isEqualTo("GET http://android.com/foo HTTP/1.1")
        assertThat(get1.headers["Proxy-Authorization"]).isNull()
        val get2 = server.takeRequest()
        assertThat(get2.requestLine).isEqualTo("GET http://android.com/foo HTTP/1.1")
        assertThat(get2.headers["Proxy-Authorization"]).isEqualTo("password")
    }

    /**
     * OkHttp has a bug where a `Connection: close` response header is not honored when establishing a
     * TLS tunnel. https://github.com/square/okhttp/issues/2426
     */
    @Test
    fun proxyAuthenticateOnConnectWithConnectionClose() {
        val handshakeCertificates = JayoTlsUtils.localhost()
        server.useHttps(handshakeCertificates.sslSocketFactory())
        server.protocols = listOf(okhttp3.Protocol.HTTP_1_1)
        server.enqueue(
            MockResponse
                .Builder()
                .code(407)
                .headers(
                    headersOf(
                        "Proxy-Authenticate",
                        "Basic realm=\"localhost\"",
                        "Connection",
                        "close",
                    ),
                ).inTunnel()
                .build(),
        )
        server.enqueue(
            MockResponse
                .Builder()
                .inTunnel()
                .build(),
        )
        server.enqueue(
            MockResponse(body = "response body"),
        )
        client =
            client
                .newBuilder()
                .tlsClientBuilder(ClientTlsEndpoint.builder(handshakeCertificates))
                .proxies(server.proxyAddress.toJayo())
                .proxyAuthenticator(RecordingJayoAuthenticator("password", "Basic"))
                .hostnameVerifier(RecordingHostnameVerifier())
                .build()
        val request = ClientRequest.get("https://android.com/foo".toHttpUrl())
        val response = client.newCall(request).execute()
        assertThat(response.body.string()).isEqualTo("response body")

        // First CONNECT call needs a new connection.
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)

        // Second CONNECT call needs a new connection.
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(0)

        // GET reuses the connection from the second connect.
        assertThat(server.takeRequest().exchangeIndex).isEqualTo(1)
    }

    @Test
    fun tooManyProxyAuthFailuresWithConnectionClose() {
        val handshakeCertificates = JayoTlsUtils.localhost()
        server.useHttps(handshakeCertificates.sslSocketFactory())
        server.protocols = listOf(okhttp3.Protocol.HTTP_1_1)
        repeat(21) {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(407)
                    .headers(
                        headersOf(
                            "Proxy-Authenticate",
                            "Basic realm=\"localhost\"",
                            "Connection",
                            "close",
                        ),
                    ).inTunnel()
                    .build(),
            )
        }
        client =
            client
                .newBuilder()
                .tlsClientBuilder(ClientTlsEndpoint.builder(handshakeCertificates))
                .proxies(server.proxyAddress.toJayo())
                .proxyAuthenticator(RecordingJayoAuthenticator("password", "Basic"))
                .hostnameVerifier(RecordingHostnameVerifier())
                .build()
        val request = ClientRequest.get("https://android.com/foo".toHttpUrl())
        assertFailsWith<JayoProtocolException> {
            client.newCall(request).execute()
        }
    }

    /**
     * Confirm that we don't send the Proxy-Authorization header from the request to the proxy server.
     * We used to have that behavior, but it is problematic because unrelated requests end up sharing credentials.
     * Worse, that approach leaks proxy credentials to the origin server.
     */
    @Test
    fun noPreemptiveProxyAuthorization() {
        val handshakeCertificates = JayoTlsUtils.localhost()
        server.useHttps(handshakeCertificates.sslSocketFactory())
        server.enqueue(
            MockResponse
                .Builder()
                .inTunnel()
                .build(),
        )
        server.enqueue(MockResponse(body = "response body"))
        client =
            client
                .newBuilder()
                .tlsClientBuilder(ClientTlsEndpoint.builder(handshakeCertificates))
                .proxies(server.proxyAddress.toJayo())
                .hostnameVerifier(RecordingHostnameVerifier())
                .build()
        val request = ClientRequest.builder()
            .url("https://android.com/foo")
            .header("Proxy-Authorization", "password")
            .get()
        val response = client.newCall(request).execute()
        assertThat(response.body.string()).isEqualTo("response body")
        val connect1 = server.takeRequest()
        assertThat(connect1.headers["Proxy-Authorization"]).isNull()
        val connect2 = server.takeRequest()
        assertThat(connect2.headers["Proxy-Authorization"]).isEqualTo("password")
    }

    /** Confirm that we can send authentication information without being prompted first.  */
    @Test
    fun preemptiveProxyAuthentication() {
        val handshakeCertificates = JayoTlsUtils.localhost()
        server.useHttps(handshakeCertificates.sslSocketFactory())
        server.enqueue(
            MockResponse
                .Builder()
                .inTunnel()
                .build(),
        )
        server.enqueue(MockResponse(body = "encrypted response from the origin server"))
        val credential = basic("pull-vert", "password1")
        client =
            client
                .newBuilder()
                .tlsClientBuilder(ClientTlsEndpoint.builder(handshakeCertificates))
                .proxies(server.proxyAddress.toJayo())
                .hostnameVerifier(RecordingHostnameVerifier())
                .proxyAuthenticator { _: Route?, response: ClientResponse? ->
                    assertThat(response!!.request.method).isEqualTo("CONNECT")
                    assertThat(response.status.code).isEqualTo(HttpURLConnection.HTTP_PROXY_AUTH)
                    assertThat(response.request.url.host).isEqualTo("android.com")
                    val challenges = response.challenges()
                    assertThat(challenges[0].scheme).isEqualTo("JayoHttp-Preemptive")
                    response.request
                        .newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build()
                }.build()
        val request = ClientRequest.get("https://android.com/foo".toHttpUrl())
        executeSynchronously(request).assertSuccessful()
        val connect = server.takeRequest()
        assertThat(connect.method).isEqualTo("CONNECT")
        assertThat(connect.headers["Proxy-Authorization"]).isEqualTo(credential)
        assertThat(connect.url.encodedPath).isEqualTo("/")
        val get = server.takeRequest()
        assertThat(get.method).isEqualTo("GET")
        assertThat(get.headers["Proxy-Authorization"]).isNull()
        assertThat(get.url.encodedPath).isEqualTo("/foo")
    }

    @Test
    fun preemptiveThenReactiveProxyAuthentication() {
        platform.assumeBouncyCastle() // whatever platform

        val handshakeCertificates = JayoTlsUtils.localhost()
        server.useHttps(handshakeCertificates.sslSocketFactory())
        server.enqueue(
            MockResponse
                .Builder()
                .code(HttpURLConnection.HTTP_PROXY_AUTH)
                .headers(headersOf("Proxy-Authenticate", "Basic realm=\"localhost\""))
                .inTunnel()
                .body("proxy auth required")
                .build(),
        )
        server.enqueue(
            MockResponse
                .Builder()
                .inTunnel()
                .build(),
        )
        server.enqueue(MockResponse())
        val challengeSchemes: MutableList<String?> = kotlin.collections.ArrayList()
        val credential = basic("pull-vert", "password1")
        client =
            client
                .newBuilder()
                .tlsClientBuilder(ClientTlsEndpoint.builder(handshakeCertificates))
                .proxies(server.proxyAddress.toJayo())
                .hostnameVerifier(RecordingHostnameVerifier())
                .proxyAuthenticator { _: Route?, response: ClientResponse ->
                    val challenges = response.challenges()
                    challengeSchemes.add(challenges[0].scheme)
                    response.request
                        .newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build()
                }.build()
        val request = ClientRequest.get("https://android.com/foo".toHttpUrl())
        executeSynchronously(request).assertSuccessful()
        val connect1 = server.takeRequest()
        assertThat(connect1.method).isEqualTo("CONNECT")
        assertThat(connect1.headers["Proxy-Authorization"]).isEqualTo(credential)
        val connect2 = server.takeRequest()
        assertThat(connect2.method).isEqualTo("CONNECT")
        assertThat(connect2.headers["Proxy-Authorization"]).isEqualTo(credential)
        assertThat(challengeSchemes).containsExactly("JayoHttp-Preemptive", "Basic")
    }

    /** https://github.com/square/okhttp/issues/4915  */
    @Test
    fun proxyDisconnectsAfterRequest() {
        val handshakeCertificates = JayoTlsUtils.localhost()
        server.useHttps(handshakeCertificates.sslSocketFactory())
        server.enqueue(
            MockResponse
                .Builder()
                .inTunnel()
                .onResponseStart(CloseSocket())
                .build(),
        )
        client =
            client
                .newBuilder()
                .tlsClientBuilder(ClientTlsEndpoint.builder(handshakeCertificates))
                .proxies(server.proxyAddress.toJayo())
                .build()
        val request = ClientRequest.get(server.url("/").toJayo())
        assertFailsWith<JayoException> {
            client.newCall(request).execute()
        }
    }

    @Disabled // todo HTTP2
    @Test
    fun interceptorGetsHttp2() {
        enableProtocol(Protocol.HTTP_2)

        // Capture the protocol as it is observed by the interceptor.
        val protocolRef = AtomicReference<Protocol?>()
        val interceptor =
            Interceptor { chain: Interceptor.Chain ->
                protocolRef.set(chain.connection()!!.protocol())
                chain.proceed(chain.request())
            }
        client =
            client
                .newBuilder()
                .addNetworkInterceptor(interceptor)
                .build()

        // Make an HTTP/2 request and confirm that the protocol matches.
        server.enqueue(MockResponse())
        executeSynchronously("/")
        assertThat(protocolRef.get()).isEqualTo(Protocol.HTTP_2)
    }

    @Test
    fun serverSendsInvalidResponseHeaders() {
        server.enqueue(
            MockResponse
                .Builder()
                .status("HTP/1.1 200 OK")
                .build(),
        )
        executeSynchronously("/")
            .assertFailure("Unexpected status line: HTP/1.1 200 OK")
    }

    @Test
    fun serverSendsInvalidCodeTooLarge() {
        server.enqueue(
            MockResponse
                .Builder()
                .status("HTTP/1.1 2147483648 OK")
                .build(),
        )
        executeSynchronously("/")
            .assertFailure("Unexpected status line: HTTP/1.1 2147483648 OK")
    }

    @Test
    fun serverSendsInvalidCodeNotANumber() {
        server.enqueue(
            MockResponse
                .Builder()
                .status("HTTP/1.1 00a OK")
                .build(),
        )
        executeSynchronously("/")
            .assertFailure("Unexpected status line: HTTP/1.1 00a OK")
    }

    @Test
    fun serverSendsUnnecessaryWhitespace() {
        server.enqueue(
            MockResponse
                .Builder()
                .status(" HTTP/1.1 200 OK")
                .build(),
        )
        executeSynchronously("/")
            .assertFailure("Unexpected status line:  HTTP/1.1 200 OK")
    }

    @Test
    fun requestHeaderNameWithSpaceForbidden() {
        assertFailsWith<IllegalArgumentException> {
            ClientRequest.builder().addHeader("a b", "c")
        }.also { expected ->
            assertThat(expected.message).isEqualTo("Unexpected char 0x20 at 1 in header name: a b")
        }
    }

    @Test
    fun requestHeaderNameWithTabForbidden() {
        assertFailsWith<IllegalArgumentException> {
            ClientRequest.builder().addHeader("a\tb", "c")
        }.also { expected ->
            assertThat(expected.message).isEqualTo("Unexpected char 0x09 at 1 in header name: a\tb")
        }
    }

    @Test
    fun responseHeaderNameWithSpacePermitted() {
        server.enqueue(
            MockResponse
                .Builder()
                .clearHeaders()
                .addHeader("content-length: 0")
                .addHeaderLenient("a b", "c")
                .build(),
        )
        val call = client.newCall(ClientRequest.get(server.url("/").toJayo()))
        val response = call.execute()
        assertThat(response.header("a b")).isEqualTo("c")
    }

    @Test
    fun responseHeaderNameWithTabPermitted() {
        server.enqueue(
            MockResponse
                .Builder()
                .clearHeaders()
                .addHeader("content-length: 0")
                .addHeaderLenient("a\tb", "c")
                .build(),
        )
        val call = client.newCall(ClientRequest.get(server.url("/").toJayo()))
        val response = call.execute()
        assertThat(response.header("a\tb")).isEqualTo("c")
    }

    @Test
    fun connectFails() {
        server.close()
        executeSynchronously("/")
            .assertFailure(JayoConnectException::class.java)
    }

    @Disabled // This may fail in DNS lookup, which we don't have timeouts for.
    @Test
    fun invalidHost() {
        val request = ClientRequest.get("http://1234.1.1.1/".toHttpUrl())
        executeSynchronously(request)
            .assertFailure(UnknownHostException::class.java)
    }

    @Test
    fun uploadBodySmallChunkedEncoding() {
        upload(true, 1048576, 256)
        val recordedRequest = server.takeRequest()
        assertThat(recordedRequest.bodySize).isEqualTo(1048576)
        assertThat(recordedRequest.chunkSizes).isNotNull()
    }

    @Test
    fun uploadBodyEmptyChunkedEncoding() {
        upload(true, 0, 256)
        val recordedRequest = server.takeRequest()
        assertThat(recordedRequest.bodySize).isEqualTo(0)
        assertThat(recordedRequest.chunkSizes).isEqualTo(listOf<Int>())
    }

    @Test
    fun uploadBodyLargeChunkedEncoding() {
        upload(true, 1048576, 65536)
        val recordedRequest = server.takeRequest()
        assertThat(recordedRequest.bodySize).isEqualTo(1048576)
        assertThat(recordedRequest.chunkSizes).isNotNull()
    }

    @Test
    fun uploadBodySmallFixedLength() {
        upload(false, 1048576, 256)
        val recordedRequest = server.takeRequest()
        assertThat(recordedRequest.bodySize).isEqualTo(1048576)
        assertThat(recordedRequest.chunkSizes).isNull()
    }

    @Test
    fun uploadBodyLargeFixedLength() {
        upload(false, 1048576, 65536)
        val recordedRequest = server.takeRequest()
        assertThat(recordedRequest.bodySize).isEqualTo(1048576)
        assertThat(recordedRequest.chunkSizes).isNull()
    }

    private fun upload(
        chunked: Boolean,
        size: Int,
        writeSize: Int,
    ) {
        server.enqueue(MockResponse())
        executeSynchronously(
            ClientRequest.builder()
                .url(server.url("/").toJayo())
                .post(requestBody(chunked, size.toLong(), writeSize))
        )
    }

    /** https://github.com/square/okhttp/issues/2344  */
    @Test
    fun ipv6HostHasSquareBracesHttp1() {
        configureClientAndServerProxies(http2 = false)
        server.enqueue(
            MockResponse(body = "response body"),
        )
        val port = server.port
        val url =
            server
                .url("/")
                .newBuilder()
                .host("::1")
                .port(port)
                .build()
        val request = ClientRequest.get(url.toJayo())
        val response = client.newCall(request).execute()
        assertThat(response.body.string()).isEqualTo("response body")
        val connect = server.takeRequest()
        assertThat(connect.requestLine).isEqualTo("CONNECT [::1]:$port HTTP/1.1")
        assertThat(connect.headers["Host"]).isEqualTo("[::1]:$port")
        assertThat(connect.headers[":authority"]).isNull()
        val get = server.takeRequest()
        assertThat(get.requestLine).isEqualTo("GET / HTTP/1.1")
        assertThat(get.headers["Host"]).isEqualTo("[::1]:$port")
        assertThat(get.headers[":authority"]).isNull()
        assertThat(get.url).isEqualTo(url)
    }

    @Disabled // todo HTTP2
    @Test
    fun ipv6HostHasSquareBracesHttp2() {
        configureClientAndServerProxies(http2 = true)
        server.enqueue(
            MockResponse(body = "response body"),
        )
        val port = server.port
        val url =
            server
                .url("/")
                .newBuilder()
                .host("::1")
                .port(port)
                .build()
        val request = ClientRequest.get(url.toJayo())
        val response = client.newCall(request).execute()
        assertThat(response.body.string()).isEqualTo("response body")
        val connect = server.takeRequest()
        assertThat(connect.requestLine).isEqualTo(
            "CONNECT [::1]:$port HTTP/1.1",
        )
        assertThat(connect.headers["Host"]).isEqualTo(
            "[::1]:$port",
        )
        assertThat(connect.headers[":authority"]).isNull()
        val get = server.takeRequest()
        assertThat(get.requestLine).isEqualTo("GET / HTTP/1.1")
        assertThat(get.headers["Host"]).isNull()
        assertThat(get.headers[":authority"]).isEqualTo(
            "[::1]:$port",
        )
        assertThat(get.url).isEqualTo(url)
    }

    @Test
    fun ipv4IpHostHasNoSquareBracesHttp1() {
        configureClientAndServerProxies(http2 = false)
        server.enqueue(
            MockResponse(body = "response body"),
        )
        val port = server.port
        val url =
            server
                .url("/")
                .newBuilder()
                .host("127.0.0.1")
                .port(port)
                .build()
        val request = ClientRequest.get(url.toJayo())
        val response = client.newCall(request).execute()
        assertThat(response.body.string()).isEqualTo("response body")
        val connect = server.takeRequest()
        assertThat(connect.requestLine).isEqualTo(
            "CONNECT 127.0.0.1:$port HTTP/1.1",
        )
        assertThat(connect.headers["Host"]).isEqualTo(
            "127.0.0.1:$port",
        )
        assertThat(connect.headers[":authority"]).isNull()
        val get = server.takeRequest()
        assertThat(get.requestLine).isEqualTo("GET / HTTP/1.1")
        assertThat(get.headers["Host"]).isEqualTo(
            "127.0.0.1:$port",
        )
        assertThat(get.headers[":authority"]).isNull()
        assertThat(get.url).isEqualTo(url)
    }

    @Disabled // todo HTTP2
    @Test
    fun ipv4IpHostHasNoSquareBracesHttp2() {
        configureClientAndServerProxies(http2 = true)
        server.enqueue(
            MockResponse(body = "response body"),
        )
        val port = server.port
        val url =
            server
                .url("/")
                .newBuilder()
                .host("127.0.0.1")
                .port(port)
                .build()
        val request = ClientRequest.get(url.toJayo())
        val response = client.newCall(request).execute()
        assertThat(response.body.string()).isEqualTo("response body")
        val connect = server.takeRequest()
        assertThat(connect.requestLine).isEqualTo("CONNECT 127.0.0.1:$port HTTP/1.1")
        assertThat(connect.headers["Host"]).isEqualTo("127.0.0.1:$port")
        assertThat(connect.headers[":authority"]).isNull()
        val get = server.takeRequest()
        assertThat(get.requestLine).isEqualTo("GET / HTTP/1.1")
        assertThat(get.headers["Host"]).isNull()
        assertThat(get.headers[":authority"]).isEqualTo("127.0.0.1:$port")
        assertThat(get.url).isEqualTo(url)
    }

    @Test
    fun hostnameRequestHostHasNoSquareBracesHttp1() {
        configureClientAndServerProxies(http2 = false)
        server.enqueue(
            MockResponse(body = "response body"),
        )
        val port = server.port
        val url =
            server
                .url("/")
                .newBuilder()
                .host("any-host-name")
                .port(port)
                .build()
        val request = ClientRequest.get(url.toJayo())
        val response = client.newCall(request).execute()
        assertThat(response.body.string()).isEqualTo("response body")
        val connect = server.takeRequest()
        assertThat(connect.requestLine).isEqualTo("CONNECT any-host-name:$port HTTP/1.1")
        assertThat(connect.headers["Host"]).isEqualTo("any-host-name:$port")
        assertThat(connect.headers[":authority"]).isNull()
        val get = server.takeRequest()
        assertThat(get.requestLine).isEqualTo("GET / HTTP/1.1")
        assertThat(get.headers["Host"]).isEqualTo("any-host-name:$port")
        assertThat(get.headers[":authority"]).isNull()
        assertThat(get.url).isEqualTo(url)
    }

    @Disabled // todo HTTP2
    @Test
    fun hostnameRequestHostHasNoSquareBracesHttp2() {
        configureClientAndServerProxies(http2 = true)
        server.enqueue(
            MockResponse(body = "response body"),
        )
        val port = server.port
        val url =
            server
                .url("/")
                .newBuilder()
                .host("any-host-name")
                .port(port)
                .build()
        val request = ClientRequest.get(url.toJayo())
        val response = client.newCall(request).execute()
        assertThat(response.body.string()).isEqualTo("response body")
        val connect = server.takeRequest()
        assertThat(connect.requestLine).isEqualTo("CONNECT any-host-name:$port HTTP/1.1")
        assertThat(connect.headers["Host"]).isEqualTo("any-host-name:$port")
        assertThat(connect.headers[":authority"]).isNull()
        val get = server.takeRequest()
        assertThat(get.requestLine).isEqualTo("GET / HTTP/1.1")
        assertThat(get.headers["Host"]).isNull()
        assertThat(get.headers[":authority"]).isEqualTo("any-host-name:$port")
        assertThat(get.url).isEqualTo(url)
    }

    /** Use a proxy to fake IPv6 connectivity, even if localhost doesn't have IPv6.  */
    private fun configureClientAndServerProxies(http2: Boolean) {
        val handshakeCertificates = JayoTlsUtils.localhost()
        server.useHttps(handshakeCertificates.sslSocketFactory())
        server.protocols =
            when {
                http2 -> listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1)
                else -> listOf(okhttp3.Protocol.HTTP_1_1)
            }
        server.enqueue(
            MockResponse
                .Builder()
                .inTunnel()
                .build(),
        )
        client =
            client
                .newBuilder()
                .tlsClientBuilder(ClientTlsEndpoint.builder(handshakeCertificates))
                .hostnameVerifier(RecordingHostnameVerifier())
                .proxies(server.proxyAddress.toJayo())
                .build()
    }

    private fun requestBody(
        chunked: Boolean,
        size: Long,
        writeSize: Int,
    ): ClientRequestBody {
        val buffer = ByteArray(writeSize)
        Arrays.fill(buffer, 'x'.code.toByte())
        return object : ClientRequestBody() {
            override fun contentType() = "text/plain; charset=utf-8".toMediaType()

            override fun contentByteSize(): Long = if (chunked) -1L else size

            override fun writeTo(destination: Writer) {
                var count = 0
                while (count < size) {
                    destination.write(
                        buffer,
                        0,
                        (size - count).coerceAtMost(writeSize.toLong()).toInt()
                    )
                    count += writeSize
                }
            }
        }
    }

    @Test
    fun emptyResponseBody() {
        server.enqueue(
            MockResponse(headers = headersOf("abc", "def")),
        )
        executeSynchronously("/")
            .assertCode(200)
            .assertHeader("abc", "def")
            .assertBody("")
    }

    @Test
    fun leakedResponseBodyLogsStackTrace() {
        server.enqueue(
            MockResponse(body = "This gets leaked."),
        )
        client =
            clientTestRule
                .newClientBuilder()
                .connectionPool(ConnectionPool.create(0, Duration.ofMillis(10)))
                .build()
        val request = ClientRequest.get(server.url("/").toJayo())
        client.newCall(request).execute() // Ignore the response so it gets leaked then GC'd.
        awaitGarbageCollection()
        val message = testLogHandler.take()
        assertThat(message).contains(
            "A connection to ${server.url("/")} was leaked. Did you forget to close a response body?",
        )
    }

    @Test
    fun asyncLeakedResponseBodyLogsStackTrace() {
        server.enqueue(MockResponse(body = "This gets leaked."))
        client =
            clientTestRule
                .newClientBuilder()
                .connectionPool(ConnectionPool.create(0, Duration.ofMillis(10)))
                .build()
        val request = ClientRequest.get(server.url("/").toJayo())
        val latch = CountDownLatch(1)
        client.newCall(request).enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    je: JayoException,
                ) {
                    fail("")
                }

                override fun onResponse(
                    call: Call,
                    response: ClientResponse,
                ) {
                    // Ignore the response so it gets leaked then GC'd.
                    latch.countDown()
                }
            },
        )
        latch.await()
        // There's some flakiness when triggering a GC for objects in a separate thread. Adding a
        // small delay appears to ensure the objects will get GC'd.
        Thread.sleep(200)
        awaitGarbageCollection()
        val message = testLogHandler.take()
        assertThat(message).contains(
            "A connection to ${server.url("/")} was leaked. Did you forget to close a response body?",
        )
    }

    @Test
    fun failedAuthenticatorReleasesConnection() {
        server.enqueue(MockResponse(code = 401))
        client =
            client
                .newBuilder()
                .authenticator { _: Route?, _: ClientResponse -> throw JayoException("IOException!") }
                .build()
        val request = ClientRequest.get(server.url("/").toJayo())
        executeSynchronously(request)
            .assertFailure(JayoException::class.java)
        assertThat(client.connectionPool.idleConnectionCount()).isEqualTo(1)
    }

    @Test
    fun failedProxyAuthenticatorReleasesConnection() {
        server.enqueue(MockResponse(code = 407))
        client =
            client
                .newBuilder()
                .proxyAuthenticator { _: Route?, _: ClientResponse -> throw JayoException("IOException!") }
                .build()
        val request = ClientRequest.get(server.url("/").toJayo())
        executeSynchronously(request)
            .assertFailure(JayoException::class.java)
        assertThat(client.connectionPool.idleConnectionCount()).isEqualTo(1)
    }

    @Test
    fun httpsWithIpAddress() {
        platform.assumeNotBouncyCastle()

        val localIpAddress = InetAddress.getLoopbackAddress().hostAddress

        // Create a certificate with an IP address in the subject alt name.
        val heldCertificate = HeldCertificate.builder()
            .commonName("example.com")
            .addSubjectAlternativeName(localIpAddress!!)
            .build()
        val handshakeCertificates = ClientHandshakeCertificates.builder()
            .heldCertificate(heldCertificate)
            .addTrustedCertificate(heldCertificate.certificate)
            .build()

        // Use that certificate on the server and trust it on the client.
        server.useHttps(handshakeCertificates.sslSocketFactory())
        client =
            client
                .newBuilder()
                .tlsClientBuilder(ClientTlsEndpoint.builder(handshakeCertificates))
                .hostnameVerifier(RecordingHostnameVerifier())
                .protocols(listOf(Protocol.HTTP_1_1))
                .build()

        // Make a request.
        server.enqueue(MockResponse())
        val url =
            server
                .url("/")
                .newBuilder()
                .host(localIpAddress)
                .build()
        val request = ClientRequest.get(url.toJayo())
        executeSynchronously(request)
            .assertCode(200)

        // Confirm that the IP address was used in the host header.
        val recordedRequest = server.takeRequest()
        assertThat(recordedRequest.headers["Host"]).isEqualTo("$localIpAddress:${server.port}")
    }

    @Test
    fun postWithFileNotFound() {
        val called = AtomicInteger(0)
        val body: ClientRequestBody =
            object : ClientRequestBody() {
                override fun contentType(): MediaType = "application/octet-stream".toMediaType()

                override fun writeTo(destination: Writer) {
                    called.incrementAndGet()
                    throw JayoFileNotFoundException("")
                }
            }
        val request = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .post(body)
        client =
            client
                .newBuilder()
                .dns(DoubleInetAddressDns())
                .build()
        executeSynchronously(request)
            .assertFailure(JayoFileNotFoundException::class.java)
        assertThat(called.get()).isEqualTo(1)
    }

    @Test
    fun clientReadsHeadersDataTrailersHttp1ChunkedTransferEncoding() {
        server.enqueue(
            MockResponse
                .Builder()
                .clearHeaders()
                .addHeader("h1", "v1")
                .addHeader("h2", "v2")
                .chunkedBody("HelloBonjour", 1024)
                .trailers(headersOf("trailers", "boom"))
                .build(),
        )
        val call = client.newCall(ClientRequest.get(server.url("/").toJayo()))
        val response = call.execute()
        val source = response.body.reader()
        assertThat(response.header("h1")).isEqualTo("v1")
        assertThat(response.header("h2")).isEqualTo("v2")
        assertThat(source.readString(5)).isEqualTo("Hello")
        assertThat(source.readString(7)).isEqualTo("Bonjour")
        assertThat(source.exhausted()).isTrue()
        assertThat(response.trailers()).isEqualTo(Headers.of("trailers", "boom"))
    }

    @Disabled // todo HTTP2
    @Test
    fun clientReadsHeadersDataTrailersHttp2() {
        server.enqueue(
            MockResponse
                .Builder()
                .clearHeaders()
                .addHeader("h1", "v1")
                .addHeader("h2", "v2")
                .body("HelloBonjour")
                .trailers(headersOf("trailers", "boom"))
                .build(),
        )
        enableProtocol(Protocol.HTTP_2)
        val call = client.newCall(ClientRequest.get(server.url("/").toJayo()))
        call.execute().use { response ->
            val source = response.body.reader()
            assertThat(response.header("h1")).isEqualTo("v1")
            assertThat(response.header("h2")).isEqualTo("v2")
            assertThat(source.readString(5)).isEqualTo("Hello")
            assertThat(source.readString(7)).isEqualTo("Bonjour")
            assertThat(source.exhausted()).isTrue()
            assertThat(response.trailers()).isEqualTo(Headers.of("trailers", "boom"))
        }
    }

    @Test
    fun connectionIsImmediatelyUnhealthy() {
        val listener: EventListener =
            object : EventListener() {
                override fun connectionAcquired(
                    call: Call,
                    connection: Connection,
                ) {
                    connection.endpoint().close()
                }
            }
        client =
            client
                .newBuilder()
                .eventListener(listener)
                .build()
        executeSynchronously("/")
            .assertFailure(JayoClosedResourceException::class.java)
    }

    @Test
    fun requestBodyThrowsUnrelatedToNetwork() {
        server.enqueue(MockResponse())
        val request = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .post(
                object : ClientRequestBody() {
                    override fun contentType(): MediaType? = null

                    override fun writeTo(destination: Writer) {
                        destination.flush() // For determinism, always send a partial request to the server.
                        throw JayoException("boom")
                    }
                },
            )
        executeSynchronously(request).assertFailure("boom")
        assertThat(server.takeRequest().failure).isNotNull()
    }

    @Test
    fun requestBodyThrowsUnrelatedToNetwork_HTTP2() {
        enableProtocol(Protocol.HTTP_2)
        requestBodyThrowsUnrelatedToNetwork()
    }

    /**
     * This test cancels the call just after the response body ends. In effect, we end up with a connection that returns
     * to the connection pool with the underlying socket closed. This relies on an implementation detail, so it might
     * not be a valid test case in the future.
     */
    @Test
    fun cancelAfterResponseBodyEnd() {
        enableTls()
        server.enqueue(MockResponse(body = "abc"))
        server.enqueue(MockResponse(body = "def"))
        client =
            client
                .newBuilder()
                .protocols(listOf(Protocol.HTTP_1_1))
                .build()
        val cancelClient =
            client
                .newBuilder()
                .eventListener(
                    object : EventListener() {
                        override fun responseBodyEnd(
                            call: Call,
                            byteCount: Long,
                        ) {
                            call.cancel()
                        }
                    },
                ).build()
        val call = cancelClient.newCall(ClientRequest.get(server.url("/").toJayo()))
        val response = call.execute()
        assertThat(response.body.string()).isEqualTo("abc")
        executeSynchronously("/").assertCode(200)
    }

    /** https://github.com/square/okhttp/issues/4583  */
    @Test
    fun lateCancelCallsOnFailure() {
        server.enqueue(
            MockResponse(body = "abc"),
        )
        val closed = AtomicBoolean()
        client =
            client
                .newBuilder()
                .addInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    chain.call().cancel() // Cancel after we have the response.
                    val closeTrackingSource =
                        object : ForwardingRawReader(response.body.reader()) {
                            override fun close() {
                                closed.set(true)
                                super.close()
                            }
                        }
                    response
                        .newBuilder()
                        .body(Jayo.buffer(closeTrackingSource).asResponseBody())
                        .build()
                }.build()
        executeSynchronously("/").assertFailure("Canceled")
        assertThat(closed.get()).isTrue()
    }

    @Test
    fun priorResponseBodyNotReadable() {
        server.enqueue(
            MockResponse(
                code = 301,
                headers =
                    headersOf(
                        "Location",
                        "/b",
                        "Content-Type",
                        "text/plain; charset=UTF-8",
                        "Test",
                        "Redirect from /a to /b",
                    ),
                body = "/a has moved!",
            ),
        )
        server.enqueue(
            MockResponse(body = "this is the redirect target"),
        )

        val call = client.newCall(ClientRequest.get(server.url("/").toJayo()))
        val response = call.execute()
        assertThat(response.body.string()).isEqualTo("this is the redirect target")
        assertThat(response.priorResponse?.body?.contentType())
            .isEqualTo("text/plain; charset=UTF-8".toMediaType())
        assertFailsWith<IllegalStateException> {
            response.priorResponse?.body?.string()
        }.also { expected ->
            assertThat(expected.message!!).contains("Unreadable ResponseBody!")
        }
    }

    private fun makeFailingCall() {
        val requestBody =
            object : ClientRequestBody() {
                override fun contentType() = null

                override fun contentByteSize() = 1L

                override fun writeTo(destination: Writer) {
                    destination.flush() // For determinism, always send a partial request to the server.
                    throw JayoException("write body fail!")
                }
            }
        val nonRetryingClient =
            client
                .newBuilder()
                .retryOnConnectionFailure(false)
                .build()
        val call =
            nonRetryingClient.newCall(
                ClientRequest.builder()
                    .url(server.url("/").toJayo())
                    .post(requestBody)
            )
        assertFailsWith<JayoException> {
            call.execute()
        }.also { expected ->
            assertThat(expected.message).isEqualTo("write body fail!")
        }
    }

    private fun executeSynchronously(
        path: String,
        vararg headers: String?,
    ): RecordedResponse {
        val builder = ClientRequest.builder()
        builder.url(server.url(path).toJayo())
        var i = 0
        val size = headers.size
        while (i < size) {
            builder.addHeader(headers[i]!!, headers[i + 1]!!)
            i += 2
        }
        return executeSynchronously(builder.get())
    }

    private fun executeSynchronously(request: ClientRequest): RecordedResponse {
        val call = client.newCall(request)
        return try {
            val response = call.execute()
            val bodyString = response.body.string()
            RecordedResponse(request, response, bodyString, null)
        } catch (e: JayoException) {
            RecordedResponse(request, null, null, e)
        }
    }

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
                .tlsClientBuilder(ClientTlsEndpoint.builder(handshakeCertificates))
                .hostnameVerifier(RecordingHostnameVerifier())
                .build()
        server.useHttps(handshakeCertificates.sslSocketFactory())
    }

    private fun gzip(data: String): okio.Buffer {
        val result = Buffer()
        val sink = GzipRawWriter(result).buffered()
        sink.write(data)
        sink.close()
        return okio.Buffer()
            .write(result.readByteArray())
    }

    private fun cancelLater(
        call: Call,
        delay: Long,
    ): Thread {
        val thread =
            object : Thread("canceler") {
                override fun run() {
                    try {
                        sleep(delay)
                    } catch (_: InterruptedException) {
                        throw kotlin.AssertionError()
                    }
                    call.cancel()
                }
            }
        thread.start()
        return thread
    }
}
