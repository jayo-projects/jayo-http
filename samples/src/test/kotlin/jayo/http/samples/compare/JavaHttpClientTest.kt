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

package jayo.http.samples.compare

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.net.http.HttpClient.Redirect.NORMAL
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.util.regex.Pattern

/**
 * Java HTTP Client.
 *
 * https://openjdk.java.net/groups/net/httpclient/intro.html
 *
 * Baseline test if we need to validate Jayo HTTP behavior against other popular clients.
 */
class JavaHttpClientTest {

    @StartStop
    private val server = MockWebServer()

    @Test
    fun get() {
        val httpClient = HttpClient.newBuilder()
            .followRedirects(NORMAL)
            .build()

        server.enqueue(
            MockResponse.Builder()
                .body("hello, Java HTTP Client")
                .build(),
        )

        val request =
            HttpRequest
                .newBuilder(server.url("/").toUri())
                .header("Accept", "text/plain")
                .build()

        val response = httpClient.send(request, BodyHandlers.ofString())
        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).isEqualTo("hello, Java HTTP Client")

        val recorded = server.takeRequest()
        assertThat(recorded.headers["Accept"]).isEqualTo("text/plain")
        assertThat(recorded.headers["Accept-Encoding"]).isNull() // No built-in gzip.
        assertThat(recorded.headers["Connection"]).isEqualTo("Upgrade, HTTP2-Settings")
        assertThat(recorded.headers["HTTP2-Settings"]).isNotNull()
        assertThat(recorded.headers["Upgrade"]).isEqualTo("h2c") // HTTP/2 over plaintext!
        assertThat(recorded.headers["User-Agent"]!!).matches(Pattern.compile("Java-http-client/.*"))
    }
}
