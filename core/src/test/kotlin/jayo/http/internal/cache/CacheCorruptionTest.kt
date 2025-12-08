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

package jayo.http.internal.cache

import jayo.buffered
import jayo.files.Directory
import jayo.files.File
import jayo.http.*
import jayo.http.internal.JavaNetCookieJar
import jayo.tls.ClientTlsSocket
import jayo.tools.JayoTlsUtils
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import okhttp3.Headers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.net.CookieManager
import java.net.ResponseCache
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import kotlin.io.path.deleteIfExists
import kotlin.io.path.name

class CacheCorruptionTest {
    @JvmField
    @RegisterExtension
    val clientTestRule = JayoHttpClientTestRule()
    private lateinit var client: JayoHttpClient
    private lateinit var cache: Cache
    private lateinit var cacheDirectory: Directory
    private val nullHostnameVerifier = HostnameVerifier { _: String?, _: SSLSession? -> true }
    private val cookieManager = CookieManager()

    @StartStop
    private val server = MockWebServer()

    @BeforeEach
    fun setUp() {
        server.protocolNegotiationEnabled = false

        val cacheDir = Files.createTempDirectory("cache-")
        cacheDir.deleteIfExists()
        cache = Cache.create(cacheDir, Int.MAX_VALUE.toLong())
        cacheDirectory = Directory.open(cacheDir)

        client =
            clientTestRule
                .newClientBuilder()
                .cache(cache)
                .cookieJar(JavaNetCookieJar(cookieManager))
                .build()
    }

    @AfterEach
    fun tearDown() {
        ResponseCache.setDefault(null)
        if (this::cache.isInitialized) {
            cache.delete()
        }
    }

    @Test
    fun corruptedCipher() {
        val response =
            testCorruptingCache {
                corruptMetadata {
                    // mess with cipher suite
                    it.replace("TLS_", "SLT_")
                }
            }

        assertThat(response.body.string()).isEqualTo("ABC.1") // cached
        assertThat(cache.requestCount()).isEqualTo(2)
        assertThat(cache.networkCount()).isEqualTo(1)
        assertThat(cache.hitCount()).isEqualTo(1)

        assertThat(response.handshake!!.cipherSuite.javaName).startsWith("SLT_")
    }

    @Test
    fun truncatedMetadataEntry() {
        val response =
            testCorruptingCache {
                corruptMetadata {
                    // truncate metadata to 1/4 of length
                    it.substring(0, it.length / 4)
                }
            }

        assertThat(response.body.string()).isEqualTo("ABC.2") // not cached
        assertThat(cache.requestCount()).isEqualTo(2)
        assertThat(cache.networkCount()).isEqualTo(2)
        assertThat(cache.hitCount()).isEqualTo(0)
    }

    @Test
    fun corruptedUrl() {
        val response =
            testCorruptingCache {
                corruptMetadata {
                    // strip https scheme
                    it.substring(5)
                }
            }

        assertThat(response.body.string()).isEqualTo("ABC.2") // not cached
        assertThat(cache.requestCount()).isEqualTo(2)
        assertThat(cache.networkCount()).isEqualTo(2)
        assertThat(cache.hitCount()).isEqualTo(0)
    }

    @Test
    fun corruptedCertificate() {
        val response =
            testCorruptingCache {
                corruptMetadata {
                    it.replace("MII", "!!!")
                }
            }

        assertThat(response.body.string()).isEqualTo("ABC.2") // not cached
        assertThat(cache.requestCount()).isEqualTo(2)
        assertThat(cache.networkCount()).isEqualTo(2)
        assertThat(cache.hitCount()).isEqualTo(0)
    }

    private fun corruptMetadata(corruptor: (String) -> String) {
        val metadataPath =
            cacheDirectory.listEntries().find {
                it.name.endsWith(".0")
            }

        if (metadataPath != null) {
            val metadataFile = File.open(metadataPath)
            val contents = metadataFile.reader().buffered().use {
                it.readString()
            }

            metadataFile.writer(StandardOpenOption.TRUNCATE_EXISTING).buffered().use {
                it.write(corruptor(contents))
            }
        }
    }

    private fun testCorruptingCache(corruptor: () -> Unit): ClientResponse {
        val handshakeCertificates = JayoTlsUtils.localhost()
        server.useHttps(handshakeCertificates.sslSocketFactory())
        server.enqueue(
            MockResponse(
                headers =
                    Headers.headersOf(
                        "Last-Modified",
                        formatDate(-1, TimeUnit.HOURS)!!,
                        "Expires",
                        formatDate(1, TimeUnit.HOURS)!!,
                    ),
                body = "ABC.1",
            ),
        )
        server.enqueue(
            MockResponse(
                headers =
                    Headers.headersOf(
                        "Last-Modified",
                        formatDate(-1, TimeUnit.HOURS)!!,
                        "Expires",
                        formatDate(1, TimeUnit.HOURS)!!,
                    ),
                body = "ABC.2",
            ),
        )
        client =
            client
                .newBuilder()
                .tlsConfig(ClientTlsSocket.builder(handshakeCertificates))
                .hostnameVerifier(nullHostnameVerifier)
                .build()
        val request = ClientRequest.get(server.url("/").toJayo())
        val response1 = client.newCall(request).execute()
        val bodyReader = response1.body.reader()
        assertThat(bodyReader.readString()).isEqualTo("ABC.1")

        corruptor()

        return client.newCall(request).execute()
    }

    /**
     * @param delta the offset from the current date to use. Negative values yield dates in the past;
     *     positive values yield dates in the future.
     */
    private fun formatDate(
        delta: Long,
        timeUnit: TimeUnit,
    ): String? = formatDate(Date(System.currentTimeMillis() + timeUnit.toMillis(delta)))

    private fun formatDate(date: Date): String? {
        val rfc1123: DateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        rfc1123.timeZone = TimeZone.getTimeZone("GMT")
        return rfc1123.format(date)
    }
}