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
import jayo.tls.Protocol
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect.ShutdownConnection
import mockwebserver3.junit5.StartStop
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertFailsWith

class InterceptorTest {
    @RegisterExtension
    val clientTestRule = JayoHttpClientTestRule()

    @StartStop
    private val server = MockWebServer()

    private var client = clientTestRule.newClient()
    private val callback = RecordingCallback()

    @Test
    fun applicationInterceptorsCanShortCircuitResponses() {
        server.close() // Accept no connections.
        val request =
            ClientRequest.builder()
                .url("https://localhost:1/")
                .get()
        val interceptorResponse =
            ClientResponse.builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .statusCode(200)
                .statusMessage("Intercepted!")
                .body(ClientResponseBody.create("abc", MediaType.get("text/plain; charset=utf-8")))
                .build()
        client =
            client
                .newBuilder()
                .addInterceptor { interceptorResponse }
                .build()
        val response = client.newCall(request).execute()
        assertThat(response).isSameAs(interceptorResponse)
    }

    @Test
    fun networkInterceptorsCannotShortCircuitResponses() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(500)
                .build(),
        )
        val interceptor =
            Interceptor { chain: Interceptor.Chain ->
                ClientResponse.builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .statusCode(200)
                    .statusMessage("Intercepted!")
                    .body(ClientResponseBody.create("abc", MediaType.get("text/plain; charset=utf-8")))
                    .build()
            }
        client =
            client
                .newBuilder()
                .addNetworkInterceptor(interceptor)
                .build()
        val request =
            ClientRequest.builder()
                .url(server.url("/").toJayo())
                .get()
        assertFailsWith<IllegalStateException> {
            client.newCall(request).execute()
        }.also { expected ->
            assertThat(expected.message).isEqualTo(
                "network interceptor $interceptor must call proceed() exactly once",
            )
        }
    }

    @Test
    fun networkInterceptorsCannotCallProceedMultipleTimes() {
        server.enqueue(MockResponse())
        server.enqueue(MockResponse())
        val interceptor =
            Interceptor { chain: Interceptor.Chain ->
                chain.proceed(chain.request())
                chain.proceed(chain.request())
            }
        client =
            client
                .newBuilder()
                .addNetworkInterceptor(interceptor)
                .build()
        val request =
            ClientRequest.builder()
                .url(server.url("/").toJayo())
                .get()
        assertFailsWith<IllegalStateException> {
            client.newCall(request).execute()
        }.also { expected ->
            assertThat(expected.message).isEqualTo(
                "network interceptor $interceptor must call proceed() exactly once",
            )
        }
    }

    @Test
    fun networkInterceptorsCannotChangeServerAddress() {
        server.enqueue(
            MockResponse
                .Builder()
                .code(500)
                .build(),
        )
        val interceptor =
            Interceptor { chain: Interceptor.Chain ->
                val address = chain.connection()!!.route().address
                val sameHost = address.url.host
                val differentPort = address.url.port + 1
                chain.proceed(
                    chain
                        .request()
                        .newBuilder()
                        .url("http://$sameHost:$differentPort/")
                        .build(),
                )
            }
        client =
            client
                .newBuilder()
                .addNetworkInterceptor(interceptor)
                .build()
        val request =
            ClientRequest.builder()
                .url(server.url("/").toJayo())
                .get()
        assertFailsWith<IllegalStateException> {
            client.newCall(request).execute()
        }.also { expected ->
            assertThat(expected.message).isEqualTo(
                "network interceptor $interceptor must retain the same host and port",
            )
        }
    }

    @Test
    fun networkInterceptorsHaveConnectionAccess() {
        server.enqueue(MockResponse())
        val interceptor =
            Interceptor { chain: Interceptor.Chain ->
                val connection = chain.connection()
                assertThat(connection).isNotNull()
                chain.proceed(chain.request())
            }
        client =
            client
                .newBuilder()
                .addNetworkInterceptor(interceptor)
                .build()
        val request =
            ClientRequest.builder()
                .url(server.url("/").toJayo())
                .get()
        client.newCall(request).execute()
    }

    @Test
    fun networkInterceptorsObserveNetworkHeaders() {
        server.enqueue(
            MockResponse
                .Builder()
                .body(gzip("abcabcabc"))
                .addHeader("Content-Encoding: gzip")
                .build(),
        )
        val interceptor =
            Interceptor { chain: Interceptor.Chain ->
                // The network request has everything: User-Agent, Host, Accept-Encoding.
                val networkRequest = chain.request()
                assertThat(networkRequest.header("User-Agent")).isNotNull()
                assertThat(networkRequest.header("Host")).isEqualTo(
                    server.hostName + ":" + server.port,
                )
                assertThat(networkRequest.header("Accept-Encoding")).isNotNull()

                // The network response also has everything, including the raw gzipped content.
                val networkResponse = chain.proceed(networkRequest)
                assertThat(networkResponse.header("Content-Encoding")).isEqualTo("gzip")
                networkResponse
            }
        client =
            client
                .newBuilder()
                .addNetworkInterceptor(interceptor)
                .build()
        val request =
            ClientRequest.builder()
                .url(server.url("/").toJayo())
                .get()

        // No extra headers in the application's request.
        assertThat(request.header("User-Agent")).isNull()
        assertThat(request.header("Host")).isNull()
        assertThat(request.header("Accept-Encoding")).isNull()

        // No extra headers in the application's response.
        val response = client.newCall(request).execute()
        assertThat(request.header("Content-Encoding")).isNull()
        assertThat(response.body.string()).isEqualTo("abcabcabc")
    }

    @Test
    fun networkInterceptorsCanChangeRequestMethodFromGetToPost() {
        server.enqueue(MockResponse())
        val interceptor =
            Interceptor { chain: Interceptor.Chain ->
                val originalRequest = chain.request()
                val mediaType = MediaType.get("text/plain")
                val body = ClientRequestBody.create("abc", mediaType)
                chain.proceed(
                    originalRequest
                        .newBuilder()
                        .header("Content-Type", mediaType.toString())
                        .header("Content-Length", body.contentByteSize().toString())
                        .method("POST", body)
                )
            }
        client =
            client
                .newBuilder()
                .addNetworkInterceptor(interceptor)
                .build()
        val request =
            ClientRequest.builder()
                .url(server.url("/").toJayo())
                .get()
        client.newCall(request).execute()
        val recordedRequest = server.takeRequest()
        assertThat(recordedRequest.method).isEqualTo("POST")
        assertThat(recordedRequest.body?.utf8()).isEqualTo("abc")
    }

    @Test
    fun applicationInterceptorsRewriteRequestToServer() {
        rewriteRequestToServer(false)
    }

    @Test
    fun networkInterceptorsRewriteRequestToServer() {
        rewriteRequestToServer(true)
    }

    private fun rewriteRequestToServer(network: Boolean) {
        server.enqueue(MockResponse())
        addInterceptor(network) { chain: Interceptor.Chain ->
            val originalRequest = chain.request()
            chain.proceed(
                originalRequest
                    .newBuilder()
                    .addHeader("OkHttp-Intercepted", "yep")
                    .method("POST", uppercase(originalRequest.body))
            )
        }
        val request =
            ClientRequest.builder()
                .url(server.url("/").toJayo())
                .addHeader("Original-Header", "foo")
                .method("PUT", ClientRequestBody.create("abc", MediaType.get("text/plain")))
        client.newCall(request).execute()
        val recordedRequest = server.takeRequest()
        assertThat(recordedRequest.body?.utf8()).isEqualTo("ABC")
        assertThat(recordedRequest.headers["Original-Header"]).isEqualTo("foo")
        assertThat(recordedRequest.headers["OkHttp-Intercepted"]).isEqualTo("yep")
        assertThat(recordedRequest.method).isEqualTo("POST")
    }

    @Test
    fun applicationInterceptorsRewriteResponseFromServer() {
        rewriteResponseFromServer(false)
    }

    @Test
    fun networkInterceptorsRewriteResponseFromServer() {
        rewriteResponseFromServer(true)
    }

    private fun rewriteResponseFromServer(network: Boolean) {
        server.enqueue(
            MockResponse
                .Builder()
                .addHeader("Original-Header: foo")
                .body("abc")
                .build(),
        )
        addInterceptor(network) { chain: Interceptor.Chain ->
            val originalResponse = chain.proceed(chain.request())
            originalResponse
                .newBuilder()
                .body(uppercase(originalResponse.body))
                .addHeader("OkHttp-Intercepted", "yep")
                .build()
        }
        val request =
            ClientRequest.builder()
                .url(server.url("/").toJayo())
                .get()
        val response = client.newCall(request).execute()
        assertThat(response.body.string()).isEqualTo("ABC")
        assertThat(response.header("OkHttp-Intercepted")).isEqualTo("yep")
        assertThat(response.header("Original-Header")).isEqualTo("foo")
    }

    @Test
    fun multipleApplicationInterceptors() {
        multipleInterceptors(false)
    }

    @Test
    fun multipleNetworkInterceptors() {
        multipleInterceptors(true)
    }

    private fun multipleInterceptors(network: Boolean) {
        server.enqueue(MockResponse())
        addInterceptor(network) { chain: Interceptor.Chain ->
            val originalRequest = chain.request()
            val originalResponse =
                chain.proceed(
                    originalRequest
                        .newBuilder()
                        .addHeader("Request-Interceptor", "Android") // 1. Added first.
                        .build(),
                )
            originalResponse
                .newBuilder()
                .addHeader("Response-Interceptor", "Donut") // 4. Added last.
                .build()
        }
        addInterceptor(network) { chain: Interceptor.Chain ->
            val originalRequest = chain.request()
            val originalResponse =
                chain.proceed(
                    originalRequest
                        .newBuilder()
                        .addHeader("Request-Interceptor", "Bob") // 2. Added second.
                        .build(),
                )
            originalResponse
                .newBuilder()
                .addHeader("Response-Interceptor", "Cupcake") // 3. Added third.
                .build()
        }
        val request =
            ClientRequest.builder()
                .url(server.url("/").toJayo())
                .get()
        val response = client.newCall(request).execute()
        assertThat(response.headers("Response-Interceptor"))
            .containsExactly("Cupcake", "Donut")
        val recordedRequest = server.takeRequest()
        assertThat(recordedRequest.headers.values("Request-Interceptor"))
            .containsExactly("Android", "Bob")
    }

    @Test
    fun asyncApplicationInterceptors() {
        asyncInterceptors(false)
    }

    @Test
    fun asyncNetworkInterceptors() {
        asyncInterceptors(true)
    }

    private fun asyncInterceptors(network: Boolean) {
        server.enqueue(MockResponse())
        addInterceptor(network) { chain: Interceptor.Chain ->
            val originalResponse = chain.proceed(chain.request())
            originalResponse
                .newBuilder()
                .addHeader("OkHttp-Intercepted", "yep")
                .build()
        }
        val request =
            ClientRequest.builder()
                .url(server.url("/").toJayo())
                .get()
        client.newCall(request).enqueue(callback)
        callback
            .await(request.url)
            .assertCode(200)
            .assertHeader("OkHttp-Intercepted", "yep")
    }

    @Test
    fun applicationInterceptorsCanMakeMultipleRequestsToServer() {
        server.enqueue(MockResponse.Builder().body("a").build())
        server.enqueue(MockResponse.Builder().body("b").build())
        client =
            client
                .newBuilder()
                .addInterceptor { chain ->
                    val response1 = chain.proceed(chain.request())
                    response1.body.close()
                    chain.proceed(chain.request())
                }.build()
        val request =
            ClientRequest.builder()
                .url(server.url("/").toJayo())
                .get()
        val response = client.newCall(request).execute()
        assertThat("b").isEqualTo(response.body.string())
    }

    /** Make sure interceptors can interact with the OkHttp client.  */
    @Test
    fun interceptorMakesAnUnrelatedRequest() {
        server.enqueue(MockResponse.Builder().body("a").build()) // Fetched by interceptor.
        server.enqueue(MockResponse.Builder().body("b").build()) // Fetched directly.
        client =
            client
                .newBuilder()
                .addInterceptor { chain ->
                    if (chain.request().url.encodedPath == "/b") {
                        val requestA =
                            ClientRequest.builder()
                                .url(server.url("/a").toJayo())
                                .get()
                        val responseA = client.newCall(requestA).execute()
                        assertThat(responseA.body.string()).isEqualTo("a")
                    }
                    chain.proceed(chain.request())
                }.build()
        val requestB =
            ClientRequest.builder()
                .url(server.url("/b").toJayo())
                .get()
        val responseB = client.newCall(requestB).execute()
        assertThat(responseB.body.string()).isEqualTo("b")
    }

    /** Make sure interceptors can interact with the OkHttp client asynchronously.  */
    @Test
    fun interceptorMakesAnUnrelatedAsyncRequest() {
        server.enqueue(MockResponse.Builder().body("a").build()) // Fetched by interceptor.
        server.enqueue(MockResponse.Builder().body("b").build()) // Fetched directly.
        client =
            client
                .newBuilder()
                .addInterceptor { chain ->
                    if (chain.request().url.encodedPath == "/b") {
                        val requestA =
                            ClientRequest.builder()
                                .url(server.url("/a").toJayo())
                                .get()
                        try {
                            val callbackA = RecordingCallback()
                            client.newCall(requestA).enqueue(callbackA)
                            callbackA.await(requestA.url).assertBody("a")
                        } catch (e: Exception) {
                            throw kotlin.RuntimeException(e)
                        }
                    }
                    chain.proceed(chain.request())
                }.build()
        val requestB =
            ClientRequest.builder()
                .url(server.url("/b").toJayo())
                .get()
        val callbackB = RecordingCallback()
        client.newCall(requestB).enqueue(callbackB)
        callbackB.await(requestB.url).assertBody("b")
    }

    @Test
    fun applicationInterceptorThrowsRuntimeExceptionSynchronous() {
        interceptorThrowsRuntimeExceptionSynchronous(false)
    }

    @Test
    fun networkInterceptorThrowsRuntimeExceptionSynchronous() {
        interceptorThrowsRuntimeExceptionSynchronous(true)
    }

    /**
     * When an interceptor throws an unexpected exception, synchronous callers can catch it and deal
     * with it.
     */
    private fun interceptorThrowsRuntimeExceptionSynchronous(network: Boolean) {
        addInterceptor(network) { throw kotlin.RuntimeException("boom!") }
        val request =
            ClientRequest.builder()
                .url(server.url("/").toJayo())
                .get()
        assertFailsWith<RuntimeException> {
            client.newCall(request).execute()
        }.also { expected ->
            assertThat(expected.message).isEqualTo("boom!")
        }
    }

    @Test
    fun networkInterceptorModifiedRequestIsReturned() {
        server.enqueue(MockResponse())
        val modifyHeaderInterceptor =
            Interceptor { chain: Interceptor.Chain ->
                val modifiedRequest =
                    chain
                        .request()
                        .newBuilder()
                        .header("User-Agent", "intercepted request")
                        .build()
                chain.proceed(modifiedRequest)
            }
        client =
            client
                .newBuilder()
                .addNetworkInterceptor(modifyHeaderInterceptor)
                .build()
        val request =
            ClientRequest.builder()
                .url(server.url("/").toJayo())
                .header("User-Agent", "user request")
                .get()
        val response = client.newCall(request).execute()
        assertThat(response.request.header("User-Agent")).isNotNull()
        assertThat(response.request.header("User-Agent")).isEqualTo("user request")
        assertThat(response.networkResponse!!.request.header("User-Agent")).isEqualTo(
            "intercepted request",
        )
    }

    @Test
    fun applicationInterceptorThrowsRuntimeExceptionAsynchronous() {
        interceptorThrowsRuntimeExceptionAsynchronous(false)
    }

    @Test
    fun networkInterceptorThrowsRuntimeExceptionAsynchronous() {
        interceptorThrowsRuntimeExceptionAsynchronous(true)
    }

    /**
     * When an interceptor throws an unexpected exception, asynchronous calls are canceled. The
     * exception goes to the uncaught exception handler.
     */
    private fun interceptorThrowsRuntimeExceptionAsynchronous(network: Boolean) {
        val boom = kotlin.RuntimeException("boom!")
        addInterceptor(network) { throw boom }
        val executor = ExceptionCatchingExecutor()
        val dispatcher = Dispatcher.builder()
            .executorService(executor)
            .build()
        client =
            client
                .newBuilder()
                .dispatcher(dispatcher)
                .build()
        val request =
            ClientRequest.builder()
                .url(server.url("/").toJayo())
                .get()
        val call = client.newCall(request)
        call.enqueue(callback)
        val recordedResponse = callback.await(server.url("/").toJayo())
        assertThat(recordedResponse.failure).message().isEqualTo("canceled due to java.lang.RuntimeException: boom!")
        assertThat(recordedResponse.failure?.cause?.cause).isEqualTo(boom)
        assertThat(call.isCanceled()).isTrue()
        assertThat(executor.takeException()).isEqualTo(boom)
    }

    @Test
    fun networkInterceptorReturnsConnectionOnEmptyBody() {
        server.enqueue(
            MockResponse
                .Builder()
                .onResponseEnd(ShutdownConnection)
                .addHeader("Connection", "Close")
                .build(),
        )
        val interceptor =
            Interceptor { chain: Interceptor.Chain ->
                val response = chain.proceed(chain.request())
                assertThat(chain.connection()).isNotNull()
                response
            }
        client =
            client
                .newBuilder()
                .addNetworkInterceptor(interceptor)
                .build()
        val request =
            ClientRequest.builder()
                .url(server.url("/").toJayo())
                .get()
        val response = client.newCall(request).execute()
        response.body.close()
    }

    @Test
    fun chainCanCancelCall() {
        val callRef = AtomicReference<Call>()
        val interceptor =
            Interceptor { chain: Interceptor.Chain ->
                val call = chain.call()
                callRef.set(call)
                assertThat(call.isCanceled()).isFalse()
                call.cancel()
                assertThat(call.isCanceled()).isTrue()
                chain.proceed(chain.request())
            }
        client =
            client
                .newBuilder()
                .addInterceptor(interceptor)
                .build()
        val request =
            ClientRequest.builder()
                .url(server.url("/").toJayo())
                .get()
        val call = client.newCall(request)
        assertFailsWith<JayoException> {
            call.execute()
        }
        assertThat(callRef.get()).isSameAs(call)
    }

    private fun uppercase(original: ClientRequestBody?): ClientRequestBody =
        object : ClientRequestBody {
            override fun contentType(): MediaType? = original!!.contentType()

            override fun contentByteSize(): Long = original!!.contentByteSize()

            override fun writeTo(destination: Writer) {
                val uppercase = uppercase(destination)
                val bufferedSink = uppercase.buffered()
                original!!.writeTo(bufferedSink)
                bufferedSink.emit()
            }
        }

    private fun uppercase(original: Writer): RawWriter =
        object : RawWriter {
            override fun writeFrom(
                source: jayo.Buffer,
                byteCount: Long,
            ) {
                original.write(source.readString(byteCount).uppercase())
            }

            override fun flush() {
                original.flush()
            }

            override fun close() {
                original.close()
            }
        }

    private fun gzip(data: String): Buffer {
        val result = Buffer()
        val sink = GzipSink(result).buffer()
        sink.writeUtf8(data)
        sink.close()
        return result
    }

    private fun addInterceptor(
        network: Boolean,
        interceptor: Interceptor,
    ) {
        val builder = client.newBuilder()
        if (network) {
            builder.addNetworkInterceptor(interceptor)
        } else {
            builder.addInterceptor(interceptor)
        }
        client = builder.build()
    }

    /** Catches exceptions that are otherwise headed for the uncaught exception handler.  */
    private class ExceptionCatchingExecutor : ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, SynchronousQueue()) {
        private val exceptions: BlockingQueue<Exception> = LinkedBlockingQueue()

        override fun execute(runnable: Runnable) {
            super.execute {
                try {
                    runnable.run()
                } catch (e: Exception) {
                    exceptions.add(e)
                }
            }
        }

        fun takeException(): Exception = exceptions.take()
    }

    companion object {
        fun uppercase(original: ClientResponseBody): ClientResponseBody =
            object : ClientResponseBody() {
                override fun contentType() = original.contentType()

                override fun contentByteSize() = original.contentByteSize()

                override fun reader() = uppercase(original.reader()).buffered()
            }

        private fun uppercase(original: Reader): RawReader {
            return object : RawReader {
                override fun readAtMostTo(
                    destination: jayo.Buffer,
                    byteCount: Long,
                ): Long {
                    val mixedCase = jayo.Buffer()
                    val count = original.readAtMostTo(mixedCase, byteCount)
                    destination.write(mixedCase.readString().uppercase())
                    return count
                }

                override fun close() {
                    original.close()
                }
            }
        }
    }
}
