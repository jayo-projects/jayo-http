/*
 * Copyright (c) 2026-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 *
 * Forked from OkHttp (https://github.com/square/okhttp), original copyright is below
 *
 * Copyright (C) 2026 OkHttp Authors
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
import jayo.http.ClientRequest
import jayo.http.EventRecorder
import jayo.http.JayoHttpClientTestRule
import jayo.http.toJayo
import jayo.tls.JssePlatformRule
import jayo.tls.Protocol
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.*
import kotlin.test.assertFailsWith

class CallLimitsTestHttp1 : CallLimitsTest(Protocol.HTTP_1_1, okhttp3.Protocol.HTTP_1_1)
class CallLimitsTestH2PriorKnowledge :
    CallLimitsTest(Protocol.H2_PRIOR_KNOWLEDGE, okhttp3.Protocol.H2_PRIOR_KNOWLEDGE)

@Timeout(20)
abstract class CallLimitsTest(private val protocol: Protocol, private val okhttpProtocol: okhttp3.Protocol) {
    @RegisterExtension
    val platform = JssePlatformRule()

    @RegisterExtension
    val clientTestRule = JayoHttpClientTestRule()

    @StartStop
    private val server = MockWebServer().apply {
        protocols = listOf(okhttpProtocol)
    }

    private var eventRecorder = EventRecorder()
    private var client =
        clientTestRule
            .newClientBuilder()
            .eventListenerFactory(clientTestRule.wrap(eventRecorder))
            .protocols(listOf(protocol))
            .build()

    @Test
    fun largeStatusLine() {
        assumeTrue(protocol == Protocol.HTTP_1_1)

        server.enqueue(
            MockResponse
                .Builder()
                .status("HTTP/1.1 200 ${"O".repeat(256 * 1024)}K")
                .body("I'm not even supposed to be here today.")
                .build(),
        )
        val call = client.newCall(ClientRequest.get(server.url("/").toJayo()))
        assertFailsWith<JayoException> {
            call.execute()
        }
    }

    /** Use a header that exceeds the limits on its own. */
    @Test
    fun largeResponseHeader() {
        server.enqueue(
            MockResponse
                .Builder()
                .addHeader("Set-Cookie", "a=${"A".repeat(256 * 1024)}")
                .body("I'm not even supposed to be here today.")
                .build(),
        )
        val call = client.newCall(ClientRequest.get(server.url("/").toJayo()))
        assertFailsWith<JayoException> {
            call.execute()
        }
    }

    /** Use a header that is large even when it is compressed. */
    @Test
    fun largeCompressedResponseHeader() {
        server.enqueue(
            MockResponse
                .Builder()
                .addHeader("Set-Cookie", "a=${randomString(256 * 1024)}")
                .body("I'm not even supposed to be here today.")
                .build(),
        )
        val call = client.newCall(ClientRequest.get(server.url("/").toJayo()))
        assertFailsWith<JayoException> {
            call.execute()
        }
    }

    /** A collection of headers that collectively exceed the limits. */
    @Test
    fun largeResponseHeadersList() {
        server.enqueue(
            MockResponse
                .Builder()
                .addHeader("Set-Cookie", "a=${"A".repeat(255 * 1024)}")
                .addHeader("Set-Cookie", "b=${"B".repeat(1 * 1024)}")
                .body("I'm not even supposed to be here today.")
                .build(),
        )
        val call = client.newCall(ClientRequest.get(server.url("/").toJayo()))
        assertFailsWith<JayoException> {
            call.execute()
        }
    }

    private fun randomString(length: Int): String {
        val byteArray = ByteArray(length)
        Random(0).nextBytes(byteArray)
        return byteArray.toByteString().base64()
    }
}
