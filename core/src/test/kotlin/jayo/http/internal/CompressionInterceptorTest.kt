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

import jayo.Buffer
import jayo.Jayo
import jayo.RawWriter
import jayo.buffered
import jayo.http.ClientRequest
import jayo.http.ClientResponse
import jayo.http.CompressionInterceptor
import jayo.http.GzipDecompressionAlgorithm.Gzip
import jayo.http.JayoHttpClientTestRule
import jayo.http.asResponseBody
import jayo.http.toHttpUrl
import jayo.http.toResponseBody
import jayo.tls.Protocol
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class CompressionInterceptorTest {
    @RegisterExtension
    val clientTestRule = JayoHttpClientTestRule()

    @Test
    fun emptyDoesntChangeRequestOrResponse() {
        val empty = CompressionInterceptor.of()
        val client =
            clientTestRule
                .newClientBuilder()
                .addInterceptor(empty)
                .addInterceptor { chain ->
                    assertThat(chain.request().header("Accept-Encoding")).isNull()

                    ClientResponse.builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .statusCode(200)
                        .statusMessage("OK")
                        .body("Hello".toResponseBody())
                        .header("Content-Encoding", "piedpiper")
                        .build()
                }.build()

        val response = client.newCall(ClientRequest.get("https://google.com/robots.txt".toHttpUrl())).execute()

        assertThat(response.header("Content-Encoding")).isEqualTo("piedpiper")
        assertThat(response.body.string()).isEqualTo("Hello")
    }

    @Test
    fun gzipThroughCall() {
        val gzip = CompressionInterceptor.of(Gzip)
        val client =
            clientTestRule
                .newClientBuilder()
                .addInterceptor(gzip)
                .addInterceptor { chain ->
                    assertThat(chain.request().header("Accept-Encoding")).isEqualTo("gzip")

                    ClientResponse.builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .statusCode(200)
                        .statusMessage("OK")
                        .body(gzip("Hello").asResponseBody())
                        .header("Content-Encoding", "gzip")
                        .build()
                }.build()

        val response = client.newCall(ClientRequest.get("https://google.com/robots.txt".toHttpUrl())).execute()

        assertThat(response.header("Content-Encoding")).isNull()
        assertThat(response.body.string()).isEqualTo("Hello")
    }

    private fun gzip(data: String): Buffer {
        val result = Buffer()
        val sink = Jayo.gzip(result as RawWriter).buffered()
        sink.write(data)
        sink.close()
        return result
    }
}
