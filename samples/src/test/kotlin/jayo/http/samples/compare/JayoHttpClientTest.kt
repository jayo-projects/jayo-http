/*
 * Copyright (c) 2025-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package jayo.http.samples.compare

import jayo.http.ClientRequest
import jayo.http.JayoHttpClient
import jayo.http.toJayo
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

/**
 * Jayo HTTP.
 *
 * https://jayo.dev/http/
 */
class JayoHttpClientTest {
    @StartStop
    private val server = MockWebServer()

    @Test
    fun get() {
        server.enqueue(MockResponse(body = "hello, Jayo HTTP"))

        val client = JayoHttpClient.create()

        val request = ClientRequest.builder()
            .url(server.url("/").toJayo())
            .header("Accept", "text/plain")
            .get()
        val response = client.newCall(request).execute()
        assertThat(response.statusCode).isEqualTo(200)
        assertThat(response.body.string()).isEqualTo("hello, Jayo HTTP")

        val recorded = server.takeRequest()
        assertThat(recorded.headers["Accept"]).isEqualTo("text/plain")
        assertThat(recorded.headers["Accept-Encoding"]).isEqualTo("gzip")
        assertThat(recorded.headers["Connection"]).isEqualTo("Keep-Alive")
        assertThat(recorded.headers["User-Agent"]!!).matches(Pattern.compile("jayohttp/.*"))
    }
}
