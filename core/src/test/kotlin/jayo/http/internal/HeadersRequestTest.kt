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

import jayo.http.ClientRequest
import jayo.http.Headers
import jayo.http.internal.http2.Http2ExchangeCodec.http2HeadersList
import jayo.http.internal.http2.Http2ExchangeCodec.readHttp2HeadersList
import jayo.http.internal.http2.Http2TestUtils.headerEntries
import jayo.tls.Protocol
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HeadersRequestTest {
    @Test
    fun readNameValueBlockDropsForbiddenHeadersHttp2() {
        val headerBlock =
            Headers.of(
                ":status",
                "200 OK",
                ":version",
                "HTTP/1.1",
                "connection",
                "close",
            )
        val request = ClientRequest.builder().url("http://square.com/").get()
        val response = readHttp2HeadersList(headerBlock, Protocol.HTTP_2).request(request).build()
        val headers = response.headers
        assertThat(headers.size).isEqualTo(1)
        assertThat(headers.name(0)).isEqualTo(":version")
        assertThat(headers.value(0)).isEqualTo("HTTP/1.1")
    }

    @Test
    fun http2HeadersListDropsForbiddenHeadersHttp2() {
        val request =
            ClientRequest.builder()
                .url("http://square.com/")
                .header("Connection", "upgrade")
                .header("Upgrade", "websocket")
                .header("Host", "square.com")
                .header("TE", "gzip")
                .get()
        val expected =
            headerEntries(
                ":method",
                "GET",
                ":path",
                "/",
                ":authority",
                "square.com",
                ":scheme",
                "http",
            )
        assertThat(http2HeadersList(request)).isEqualTo(expected)
    }

    @Test
    fun http2HeadersListDontDropTeIfTrailersHttp2() {
        val request =
            ClientRequest.builder()
                .url("http://square.com/")
                .header("TE", "trailers")
                .get()
        val expected =
            headerEntries(
                ":method",
                "GET",
                ":path",
                "/",
                ":scheme",
                "http",
                "te",
                "trailers",
            )
        assertThat(http2HeadersList(request)).isEqualTo(expected)
    }
}
