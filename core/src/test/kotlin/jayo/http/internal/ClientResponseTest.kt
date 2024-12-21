/*
 * Copyright (c) 2024-present, pull-vert and Jayo contributors.
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

import jayo.Buffer
import jayo.ByteString.EMPTY
import jayo.RawReader
import jayo.buffered
import jayo.http.*
import jayo.tls.AlpnProtocol
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class ClientResponseTest {
    @Test
    fun testEmptyByDefaultIfTrailersNotSet() {
        val response = newResponse("".toResponseBody())

        assertThat(response.trailers()).isEmpty()
    }

    @Test
    fun testFailsIfTrailersNotSet() {
        val response =
            newResponse("".toResponseBody()) {
                // All live paths (Http1, Http2) in OkHttp do this
                trailers { error("trailers not available") }
            }

        assertFailsWith<IllegalStateException>(message = "trailers not available") {
            response.trailers()
        }
    }

    @Test
    fun worksIfTrailersSet() {
        val response =
            newResponse("".toResponseBody()) {
                trailers {
                    Headers.of("a", "b")
                }
            }

        assertThat(response.trailers()["a"]).isEqualTo("b")
    }

    @Test
    fun peekAfterReadingResponse() {
        val response = newResponse(responseBody("abc"))
        assertThat(response.body.string()).isEqualTo("abc")

        assertFailsWith<IllegalStateException> {
            response.peekBody(3)
        }
    }

    @Test
    fun peekShorterThanResponse() {
        val response = newResponse(responseBody("abcdef"))
        val peekedBody = response.peekBody(3)
        assertThat(peekedBody.string()).isEqualTo("abc")
        assertThat(response.body.string()).isEqualTo("abcdef")
    }

    @Test
    fun peekLongerThanResponse() {
        val response = newResponse(responseBody("abc"))
        val peekedBody = response.peekBody(6)
        assertThat(peekedBody.string()).isEqualTo("abc")
        assertThat(response.body.string()).isEqualTo("abc")
    }

    @Test
    fun eachPeakIsIndependent() {
        val response = newResponse(responseBody("abcdef"))
        val p1 = response.peekBody(4)
        val p2 = response.peekBody(2)
        assertThat(response.body.string()).isEqualTo("abcdef")
        assertThat(p1.string()).isEqualTo("abcd")
        assertThat(p2.string()).isEqualTo("ab")
    }

    @Test
    fun negativeStatusCodeThrowsIllegalStateException() {
        assertFailsWith<IllegalStateException> {
            newResponse(responseBody("set status code -1"), -1)
        }
    }

    @Test
    fun zeroStatusCodeIsValid() {
        val response = newResponse(responseBody("set status code 0"), 0)
        assertThat(response.status.code).isEqualTo(0)
    }

    @Test
    fun defaultResponseBodyIsEmpty() {
        val response =
            ClientResponse.builder()
                .request(
                    ClientRequest.builder()
                        .url("https://example.com/")
                        .get(),
                )
                .protocol(AlpnProtocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .build()
        assertThat(response.body.contentType()).isNull()
        assertThat(response.body.contentByteSize()).isEqualTo(0L)
        assertThat(response.body.byteString()).isEqualTo(EMPTY)
    }

    /**
     * Returns a new response body that refuses to be read once it has been closed. This is true of
     * most [jayo.Reader] instances, but not of [Buffer].
     */
    private fun responseBody(content: String): ClientResponseBody {
        val data = Buffer().write(content)
        val reader: RawReader =
            object : RawReader {
                var closed = false

                override fun close() {
                    closed = true
                }

                override fun readAtMostTo(
                    sink: Buffer,
                    byteCount: Long,
                ): Long {
                    check(!closed)
                    return data.readAtMostTo(sink, byteCount)
                }
            }
        return reader.buffered().asResponseBody(null, -1)
    }

    private fun newResponse(
        responseBody: ClientResponseBody,
        code: Int = 200,
        fn: ClientResponse.Builder.() -> Unit = {},
    ): ClientResponse {
        return ClientResponse.builder()
            .request(
                ClientRequest.builder()
                    .url("https://example.com/")
                    .get(),
            )
            .protocol(AlpnProtocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .body(responseBody)
            .apply { fn() }
            .build()
    }
}
