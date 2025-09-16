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

import jayo.http.*
import jayo.tls.*
import jayo.tls.CipherSuite.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import javax.net.ssl.TrustManager

class CallHandshakeTest {
    private lateinit var client: JayoHttpClient

    @StartStop
    private val server = MockWebServer()

    @RegisterExtension
    @JvmField
    val clientTestRule = JayoHttpClientTestRule()

    @RegisterExtension
    @JvmField
    var platform = JssePlatformRule()

    val expectedModernTls12CipherSuites =
        listOf(
            TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
            TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
            TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384,
        )
    val expectedModernTls13CipherSuites =
        listOf(
            TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
            TLS_AES_128_GCM_SHA256,
            TLS_AES_256_GCM_SHA384,
        )

    @Test
    fun testDefaultHandshakeCipherSuiteOrderingTls12Compatible() {
        // We are avoiding making guarantees on ordering of secondary Platforms.
        platform.assumeNotConscrypt()
        platform.assumeNotBouncyCastle()

        setup()

        val client = makeClient(ConnectionSpec.COMPATIBLE_TLS, TlsVersion.TLS_1_2)

        val handshake = makeRequest(client)
        assertThat(handshake.cipherSuite).isIn(*expectedModernTls12CipherSuites.toTypedArray())
        assertThat(handshake.tlsVersion).isEqualTo(TlsVersion.TLS_1_2)
    }

    @Test
    fun testDefaultHandshakeCipherSuiteOrderingTls12Modern() {
        // We are avoiding making guarantees on ordering of secondary Platforms.
        platform.assumeNotConscrypt()
        platform.assumeNotBouncyCastle()

        setup()

        val client = makeClient(ConnectionSpec.MODERN_TLS, TlsVersion.TLS_1_2)

        val handshake = makeRequest(client)
        assertThat(handshake.cipherSuite).isIn(*expectedModernTls12CipherSuites.toTypedArray())
        assertThat(handshake.tlsVersion).isEqualTo(TlsVersion.TLS_1_2)
    }

    @Test
    fun testDefaultHandshakeCipherSuiteOrderingTls13Modern() {
        // We are avoiding making guarantees on ordering of secondary Platforms.
        platform.assumeNotConscrypt()
        platform.assumeNotBouncyCastle()

        setup()

        val client = makeClient(ConnectionSpec.MODERN_TLS, TlsVersion.TLS_1_3)

        val handshake = makeRequest(client)
        assertThat(handshake.cipherSuite).isIn(*expectedModernTls13CipherSuites.toTypedArray())
        assertThat(handshake.tlsVersion).isEqualTo(TlsVersion.TLS_1_3)
    }

    @Test
    fun advertisedOrderInModern() {
        assertThat(ConnectionSpec.MODERN_TLS.cipherSuites!!).containsExactly(
            TLS_AES_128_GCM_SHA256,
            TLS_AES_256_GCM_SHA384,
            TLS_CHACHA20_POLY1305_SHA256,
            TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
            TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
            TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
            TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
            TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
            TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
        )
    }

    @Test
    fun effectiveOrderInRestrictedJdk11() {
        platform.assumeJdkVersion(11)
        // We are avoiding making guarantees on ordering of secondary Platforms.
        platform.assumeNotConscrypt()
        platform.assumeNotBouncyCastle()

        val platform = JssePlatform.get()
        val platformDefaultCipherSuites =
            platform.newSSLContext()
                .apply {
                    init(null, arrayOf<TrustManager>(platform.defaultTrustManager), null)
                }.socketFactory.defaultCipherSuites
        val cipherSuites = ConnectionSpec.MODERN_TLS.effectiveCipherSuites(platformDefaultCipherSuites)

        if (cipherSuites.contains(TLS_CHACHA20_POLY1305_SHA256)) {
            assertThat(cipherSuites).containsExactly(
                TLS_AES_128_GCM_SHA256,
                TLS_AES_256_GCM_SHA384,
                TLS_CHACHA20_POLY1305_SHA256,
                TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
                TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
                TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
                TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256,
            )
        } else {
            assertThat(cipherSuites).containsExactly(
                TLS_AES_128_GCM_SHA256,
                TLS_AES_256_GCM_SHA384,
                TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
                TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
                TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
            )
        }
    }

    private fun ConnectionSpec.effectiveCipherSuites(socketEnabledCipherSuites: Array<String>) =
        RealConnectionSpec.effectiveCipherSuites(
            this as RealConnectionSpec,
            socketEnabledCipherSuites
                .map { cipherSuite -> fromJavaName(cipherSuite) },
        )

    private fun makeClient(
        connectionSpec: ConnectionSpec? = null,
        tlsVersion: TlsVersion? = null,
        cipherSuites: List<CipherSuite>? = null,
    ): JayoHttpClient =
        this.client
            .newBuilder()
            .apply {
                if (connectionSpec != null) {
                    connectionSpecs(
                        listOf(
                            ConnectionSpec.builder(connectionSpec)
                                .apply {
                                    if (tlsVersion != null) {
                                        tlsVersions(listOf(tlsVersion))
                                    }
                                    if (cipherSuites != null) {
                                        cipherSuites(cipherSuites)
                                    }
                                }.build(),
                        ),
                    )
                }
            }
            .build()

    private fun makeRequest(client: JayoHttpClient): Handshake {
        val call = client.newCall(ClientRequest.get(server.url("/").toJayo()))
        return call.execute().use { it.handshake!! }
    }

    private fun setup() {
        val handshakeCertificates = platform.localhostHandshakeCertificates()

        client =
            clientTestRule
                .newClientBuilder()
                .tlsClientBuilder(ClientTlsSocket.builder(handshakeCertificates))
                .hostnameVerifier(RecordingHostnameVerifier())
                .build()

        server.enqueue(MockResponse())
        server.useHttps(handshakeCertificates.sslSocketFactory())
    }
}
