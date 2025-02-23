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

package jayo.http.internal.connection

import jayo.http.*
import jayo.network.Proxy
import jayo.tls.ClientHandshakeCertificates
import jayo.tls.ClientTlsEndpoint
import jayo.tls.Protocol
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.net.CookieManager
import java.net.InetSocketAddress
import java.net.ResponseCache
import java.time.Duration
import javax.net.ssl.SSLSession

@Suppress("UNCHECKED_CAST")
class JayoHttpClientTest {
    @RegisterExtension
    val clientTestRule = JayoHttpClientTestRule()

    @StartStop
    private val server = MockWebServer()

    @AfterEach
    fun tearDown() {
        CookieManager.setDefault(DEFAULT_COOKIE_HANDLER)
        ResponseCache.setDefault(DEFAULT_RESPONSE_CACHE)
    }

    @Test
    fun durationDefaults() {
        val client = clientTestRule.newClient()
        assertThat(client.callTimeout).isEqualTo(Duration.ZERO)
        assertThat(client.connectTimeout).isEqualTo(Duration.ofSeconds(10L))
        assertThat(client.readTimeout).isEqualTo(Duration.ofSeconds(10L))
        assertThat(client.webSocketCloseTimeout).isEqualTo(Duration.ofSeconds(60L))
        assertThat(client.writeTimeout).isEqualTo(Duration.ofSeconds(10L))
        assertThat(client.pingIntervalMillis).isEqualTo(0)
    }

    @Test
    fun webSocketDefaults() {
        val client = clientTestRule.newClient()
        assertThat(client.minWebSocketMessageToCompress).isEqualTo(1024L)
    }

    @Test
    fun timeoutValidRange() {
        val builder = JayoHttpClient.builder()
        builder.callTimeout(Duration.ofMillis(1L))
        builder.callTimeout(Duration.ofHours(1L))

        assertThatThrownBy {
            @Suppress("NULL_FOR_NONNULL_TYPE")
            builder.callTimeout(null)
        }
            .isInstanceOf(NullPointerException::class.java)
            .hasMessage("callTimeout == null")
        assertThatThrownBy { builder.callTimeout(Duration.ofMillis(-1L)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("callTimeout < 0")
        assertThatThrownBy { builder.callTimeout(Duration.ofNanos(1L)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("callTimeout < 1ms")
        assertThatThrownBy { builder.callTimeout(Duration.ofHours(1L).plusNanos(1L)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("callTimeout > 1h")
    }

    @Test
    fun clonedInterceptorsListsAreIndependent() {
        val interceptor =
            Interceptor { chain: Interceptor.Chain ->
                chain.proceed(chain.request())
            }
        val original = clientTestRule.newClient()
        original
            .newBuilder()
            .addInterceptor(interceptor)
            .addNetworkInterceptor(interceptor)
            .build()
        assertThat(original.interceptors.size).isEqualTo(0)
        assertThat(original.networkInterceptors.size).isEqualTo(0)
    }

    /**
     * When copying the client, stateful things like the connection pool are shared across all clients.
     */
    @Test
    fun cloneSharesStatefulInstances() {
        val client = clientTestRule.newClient()

        // Values should be non-null.
        val a = client.newBuilder().build()
        assertThat(a.dispatcher).isNotNull()
        assertThat(a.connectionPool).isNotNull()
        assertThat(a.tlsClientBuilder).isNotNull()

        // Multiple clients share the instances.
        val b = client.newBuilder().build()
        assertThat(b.dispatcher).isSameAs(a.dispatcher)
        assertThat(b.connectionPool).isSameAs(a.connectionPool)
        assertThat(b.tlsClientBuilder).isSameAs(a.tlsClientBuilder)
    }

    @Test
    fun certificatePinnerEquality() {
        val clientA = clientTestRule.newClient()
        val clientB = clientTestRule.newClient()
        assertThat(clientB.certificatePinner).isEqualTo(clientA.certificatePinner)
    }

    @Test
    fun nullInterceptorInList() {
        val builder = JayoHttpClient.builder()
        @Suppress("UNCHECKED_CAST")
        builder.interceptors().addAll(listOf(null) as List<Interceptor>)
        assertThatThrownBy {
            builder.build()
        }.isInstanceOf(NullPointerException::class.java)
    }

    @Test
    fun nullNetworkInterceptorInList() {
        val builder = JayoHttpClient.builder()
        @Suppress("UNCHECKED_CAST")
        builder.networkInterceptors().addAll(listOf(null) as List<Interceptor>)
        assertThatThrownBy {
            builder.build()
        }.isInstanceOf(NullPointerException::class.java)
    }

    @Test
    fun setProtocolsRejectsHttp10() {
        val builder = JayoHttpClient.builder()
        assertThatThrownBy {
            builder.protocols(listOf(Protocol.HTTP_1_0, Protocol.HTTP_1_1))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun testH2PriorKnowledgeJayoHttpClientConstructionFallback() {
        assertThatThrownBy {
            JayoHttpClient
                .builder()
                .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE, Protocol.HTTP_1_1))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage(
                "protocols containing h2_prior_knowledge cannot use other protocols: [h2_prior_knowledge, http/1.1]"
            )
    }

    @Test
    fun testH2PriorKnowledgeJayoHttpClientConstructionDuplicates() {
        assertThatThrownBy {
            JayoHttpClient
                .builder()
                .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE, Protocol.H2_PRIOR_KNOWLEDGE))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage(
                "protocols containing h2_prior_knowledge cannot use other protocols: " +
                        "[h2_prior_knowledge, h2_prior_knowledge]"
            )
    }

    @Test
    fun testH2PriorKnowledgeJayoHttpClientConstructionSuccess() {
        val jayoHttpClient =
            JayoHttpClient.builder()
                .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
                .build()
        assertThat(jayoHttpClient.protocols.size).isEqualTo(1)
        assertThat(jayoHttpClient.protocols[0]).isEqualTo(Protocol.H2_PRIOR_KNOWLEDGE)
    }

    @Test
    fun nullHostileProtocolList() {
        val nullHostileProtocols =
            object : AbstractList<Protocol?>() {
                override val size: Int = 1

                override fun get(index: Int) = Protocol.HTTP_1_1

                override fun contains(element: Protocol?): Boolean {
                    if (element == null) throw NullPointerException()
                    return super.contains(element)
                }

                override fun indexOf(element: Protocol?): Int {
                    if (element == null) throw NullPointerException()
                    return super.indexOf(element)
                }
            } as List<Protocol>
        val client =
            JayoHttpClient
                .builder()
                .protocols(nullHostileProtocols)
                .build()
        assertThat(client.protocols).isEqualTo(listOf(Protocol.HTTP_1_1))
    }

    @Test
    fun nullProtocolInList() {
        val protocols =
            mutableListOf(
                Protocol.HTTP_1_1,
                null,
            )
        assertThatThrownBy {
            JayoHttpClient
                .builder()
                .protocols(protocols as List<Protocol>)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("protocols must not contain null")
    }

    @Test
    fun nullDefaultProxySelector() {
        server.enqueue(MockResponse(body = "abc"))
        val client = clientTestRule.newClient()
        val request = ClientRequest.builder()
            .url(server.url("/").toUrl())
            .get()
        val response = client.newCall(request).execute()
        assertThat(response.body.string()).isEqualTo("abc")
    }

    @Test
    fun noSslSocketFactoryConfigured() {
        val client =
            JayoHttpClient
                .builder()
                .connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
                .build()
        assertThatThrownBy {
            client.tlsClientBuilder
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("CLEARTEXT-only client")
    }

    @Test
    fun sharesRouteDatabase() {
        val client =
            JayoHttpClient
                .builder()
                .build() as RealJayoHttpClient

        val clientTlsEndpointBuilder =
            ClientTlsEndpoint.builder(ClientHandshakeCertificates.create())

        // a new client, it may share some fields but likely different connection pool
        assertThat(
            (JayoHttpClient
                .builder()
                .build() as RealJayoHttpClient)
                .routeDatabase
        ).isNotSameAs(client.routeDatabase)

        // same client with no change affecting route db
        assertThat(
            (client
                .newBuilder()
                .build() as RealJayoHttpClient)
                .routeDatabase
        ).isSameAs(client.routeDatabase)
        assertThat(
            (client
                .newBuilder()
                .callTimeout(Duration.ofSeconds(5))
                .build() as RealJayoHttpClient)
                .routeDatabase
        ).isSameAs(client.routeDatabase)

        // logically different scope of client for route db
        assertThat(
            (client
                .newBuilder()
                .dns { listOf() }
                .build() as RealJayoHttpClient)
                .routeDatabase
        ).isNotSameAs(client.routeDatabase)
        assertThat(
            (client
                .newBuilder()
                .proxyAuthenticator { _: Route?, _: ClientResponse? -> null }
                .build() as RealJayoHttpClient)
                .routeDatabase
        ).isNotSameAs(client.routeDatabase)
        assertThat(
            (client
                .newBuilder()
                .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
                .build() as RealJayoHttpClient)
                .routeDatabase
        ).isNotSameAs(client.routeDatabase)
        assertThat(
            (client
                .newBuilder()
                .connectionSpecs(listOf(ConnectionSpec.COMPATIBLE_TLS))
                .build() as RealJayoHttpClient)
                .routeDatabase
        ).isNotSameAs(client.routeDatabase)
        assertThat(
            (client
                .newBuilder()
                .proxies(Proxies.create(Proxy.socks4(InetSocketAddress(0))))
                .build() as RealJayoHttpClient)
                .routeDatabase
        ).isNotSameAs(client.routeDatabase)
        assertThat(
            (client
                .newBuilder()
                .tlsClientBuilder(clientTlsEndpointBuilder)
                .build() as RealJayoHttpClient)
                .routeDatabase
        ).isNotSameAs(client.routeDatabase)
        assertThat(
            (client
                .newBuilder()
                .hostnameVerifier { _: String?, _: SSLSession? -> false }
                .build() as RealJayoHttpClient)
                .routeDatabase
        ).isNotSameAs(client.routeDatabase)
        assertThat(
            (client
                .newBuilder()
                .certificatePinner(CertificatePinner.builder().build())
                .build() as RealJayoHttpClient)
                .routeDatabase
        ).isNotSameAs(client.routeDatabase)
    }

//    @Test
//    fun minWebSocketMessageToCompressNegative() {
//        val builder = JayoHttpClient.builder()
//        assertFailsWith<IllegalArgumentException> {
//            builder.minWebSocketMessageToCompress(-1024)
//        }.also { expected ->
//            assertThat(expected.message)
//                .isEqualTo("minWebSocketMessageToCompress must be positive: -1024")
//        }
//    }

    companion object {
        //        private val DEFAULT_PROXY_SELECTOR = ProxySelector.getDefault()
        private val DEFAULT_COOKIE_HANDLER = CookieManager.getDefault()
        private val DEFAULT_RESPONSE_CACHE = ResponseCache.getDefault()
    }
}