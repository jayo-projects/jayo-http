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

package jayo.http.coroutines

import jayo.Buffer
import jayo.JayoException
import jayo.RawReader
import jayo.buffered
import jayo.http.*
import jayo.tls.Protocol
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import mockwebserver3.junit5.StartStop
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.fail
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class ExecuteAsyncTest {
    @RegisterExtension
    val clientTestRule = JayoHttpClientTestRule()

    private var client = clientTestRule.newClientBuilder().build()

    @StartStop
    private val server = MockWebServer()

    val request by lazy { ClientRequest.get(server.url("/").toJayo()) }

    @Test
    fun suspendCall() {
        runTest {
            server.enqueue(MockResponse(body = "abc"))

            val call = client.newCall(request)

            call.executeAsync().use {
                withContext(Dispatchers.IO) {
                    assertThat(it.body.string()).isEqualTo("abc")
                }
            }
        }
    }

    @Test
    fun timeoutCall() {
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .bodyDelay(5, TimeUnit.SECONDS)
                    .body("abc")
                    .build(),
            )

            val call = client.newCall(request)

            assertFailsWith<TimeoutCancellationException> {
                withTimeout(1.seconds) {
                    call.executeAsync().use {
                        withContext(Dispatchers.IO) {
                            it.body.string()
                        }
                        fail("No expected to get response")
                    }
                }
            }

            assertThat(call.isCanceled()).isTrue()
        }
    }

    @Test
    fun cancelledCall() {
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .bodyDelay(5, TimeUnit.SECONDS)
                    .body("abc")
                    .build(),
            )

            val call = client.newCall(request)

            assertFailsWith<JayoException> {
                call.executeAsync().use {
                    call.cancel()
                    withContext(Dispatchers.IO) {
                        it.body.string()
                    }
                }
            }

            assertThat(call.isCanceled()).isTrue()
        }
    }

    @Test
    fun failedCall() {
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .body("abc")
                    .onResponseStart(SocketEffect.ShutdownConnection)
                    .build(),
            )

            val call = client.newCall(request)

            assertFailsWith<JayoException> {
                call.executeAsync().use {
                    withContext(Dispatchers.IO) {
                        it.body.string()
                    }
                }
            }
        }
    }

    @Test
    fun responseClosedIfCoroutineCanceled() {
        runTest {
            val call = ClosableCall()

            supervisorScope {
                assertFailsWith<CancellationException> {
                    coroutineScope {
                        call.afterCallbackOnResponse = {
                            coroutineContext.job.cancel()
                        }
                        call.executeAsync()
                    }
                }
            }

            assertThat(call.canceled).isTrue()
            assertThat(call.responseClosed).isTrue()
        }
    }

    /** A call that keeps track of whether its response body is closed. */
    private class ClosableCall : FailingCall() {
        private val response = ClientResponse.builder()
            .request(ClientRequest.get("https://example.com/".toHttpUrl()))
            .protocol(Protocol.HTTP_1_1)
            .statusMessage("OK")
            .statusCode(200)
            .body(
                object : ClientResponseBody() {
                    override fun contentType() = null

                    override fun contentByteSize() = -1L

                    override fun reader() = object : RawReader {
                        private val buffer = Buffer()

                        override fun readAtMostTo(destination: Buffer, byteCount: Long) =
                            buffer.readAtMostTo(destination, byteCount)

                        override fun close() {
                            responseClosed = true
                        }
                    }.buffered()
                },
            ).build()

        var responseClosed = false
        var canceled = false
        var afterCallbackOnResponse: () -> Unit = {}

        override fun cancel() {
            canceled = true
        }

        override fun enqueue(responseCallback: Callback) {
            responseCallback.onResponse(this, response)
            afterCallbackOnResponse()
        }
    }
}
