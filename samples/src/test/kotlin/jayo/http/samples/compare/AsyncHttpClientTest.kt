/*
 * Copyright (c) 2025-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package jayo.http.samples.compare

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.assertj.core.api.Assertions.assertThat
import org.asynchttpclient.Dsl.asyncHttpClient
import org.asynchttpclient.Dsl.get
import org.junit.jupiter.api.Test
import java.util.regex.Pattern

/**
 * Async Http Client.
 *
 * https://github.com/AsyncHttpClient/async-http-client
 *
 * Baseline test if we need to validate Jayo HTTP behavior against other popular clients.
 */
class AsyncHttpClientTest {
    @StartStop
    private val server = MockWebServer()

    @Test
    fun get() {
        server.enqueue(MockResponse(body = "hello, Async Http Client"))

        val client = asyncHttpClient()

        val request = get(server.url("/").toString())
            .setHeader("Accept", "text/plain")
            .build()
        val response = client.executeRequest(request)
            .get() // block the calling thread to get the response.
        assertThat(response.statusCode).isEqualTo(200)
        assertThat(response.responseBody).isEqualTo("hello, Async Http Client")

        val recorded = server.takeRequest()
        assertThat(recorded.headers["Accept"]).isEqualTo("text/plain")
        assertThat(recorded.headers["Accept-Encoding"]).isNull() // No built-in gzip.
        assertThat(recorded.headers["User-Agent"]!!).matches(Pattern.compile("AHC/.*"))
    }
}
