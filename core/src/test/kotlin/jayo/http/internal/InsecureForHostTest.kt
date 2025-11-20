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
import jayo.http.JayoHttpClientTestRule
import jayo.http.sslSocketFactory
import jayo.http.toJayo
import jayo.tls.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertFailsWith

class InsecureForHostTest {
    @RegisterExtension
    @JvmField
    val platform = JssePlatformRule()

    @RegisterExtension
    @JvmField
    val clientTestRule = JayoHttpClientTestRule()

    @StartStop
    private val server = MockWebServer()

    @Test
    fun `untrusted host in insecureHosts connects successfully`() {
        val serverCertificates = platform.localhostHandshakeCertificates()
        server.useHttps(serverCertificates.sslSocketFactory())
        server.enqueue(MockResponse())

        val clientCertificates =
            ClientHandshakeCertificates.builder()
                .addPlatformTrustedCertificates(true)
                .addInsecureHost(server.hostName)
                .build()

        val client =
            clientTestRule
                .newClientBuilder()
                .tlsConfig(ClientTlsSocket.builder(clientCertificates))
                .build()

        val call = client.newCall(ClientRequest.get(server.url("/").toJayo()))
        val response = call.execute()
        assertThat(response.statusCode).isEqualTo(200)
        assertThat(response.handshake!!.cipherSuite).isNotNull()
        assertThat(response.handshake!!.tlsVersion).isNotNull()
        assertThat(response.handshake!!.localCertificates).isEmpty()
        assertThat(response.handshake!!.localPrincipal).isNull()
        assertThat(response.handshake!!.peerCertificates).isEmpty()
        assertThat(response.handshake!!.peerPrincipal).isNull()
    }

    @Test
    fun `bad certificates host in insecureHosts fails with JayoTlsPeerUnverifiedException`() {
        val heldCertificate =
            HeldCertificate.builder()
                .addSubjectAlternativeName("example.com")
                .build()
        val serverCertificates = ServerHandshakeCertificates.builder(heldCertificate)
            .build()
        server.useHttps(serverCertificates.sslSocketFactory())
        server.enqueue(MockResponse())

        val clientCertificates =
            ClientHandshakeCertificates.builder()
                .addPlatformTrustedCertificates(true)
                .addInsecureHost(server.hostName)
                .build()

        val client =
            clientTestRule
                .newClientBuilder()
                .tlsConfig(ClientTlsSocket.builder(clientCertificates))
                .build()

        val call = client.newCall(ClientRequest.get(server.url("/").toJayo()))
        assertFailsWith<JayoTlsPeerUnverifiedException> {
            call.execute()
        }
    }

    @Test
    fun `untrusted host not in insecureHosts fails with JayoTlsException`() {
        val serverCertificates = platform.localhostHandshakeCertificates()
        server.useHttps(serverCertificates.sslSocketFactory())
        server.enqueue(MockResponse())

        val clientCertificates =
            ClientHandshakeCertificates.builder()
                .addPlatformTrustedCertificates(true)
                .addInsecureHost("${server.hostName}2")
                .build()

        val client =
            clientTestRule
                .newClientBuilder()
                .tlsConfig(ClientTlsSocket.builder(clientCertificates))
                .build()

        val call = client.newCall(ClientRequest.get(server.url("/").toJayo()))
        assertFailsWith<JayoTlsException> {
            call.execute()
        }
    }
}
