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

package jayo.http.internal.authentication

import jayo.http.ClientRequest
import jayo.http.ClientResponse
import jayo.http.FakeDns
import jayo.http.internal.RecordingAuthenticator
import jayo.http.internal.connection.TestValueFactory
import jayo.network.Proxy
import jayo.tls.Protocol.HTTP_2
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.Authenticator
import java.net.InetAddress
import java.net.InetSocketAddress

// More tests on authentication are in URLConnectionTest
class JavaNetAuthenticatorTest {
    private var authenticator = JavaNetAuthenticator.INSTANCE
    private val fakeDns = FakeDns()
    private val recordingAuthenticator = RecordingAuthenticator()
    private val factory =
        TestValueFactory()
            .apply {
                dns = fakeDns
            }

    @BeforeEach
    fun setup() {
        Authenticator.setDefault(recordingAuthenticator)
    }

    @AfterEach
    fun tearDown() {
        Authenticator.setDefault(null)
        factory.close()
    }

    @Test
    fun testBasicAuth() {
        fakeDns["server"] = listOf(InetAddress.getLocalHost())

        val route = factory.newRoute()

        val request = ClientRequest.builder()
            .url("https://server/robots.txt")
            .get()
        val response = ClientResponse.builder()
            .request(request)
            .statusCode(401)
            .header("WWW-Authenticate", "Basic realm=\"User Visible Realm\"")
            .protocol(HTTP_2)
            .statusMessage("Unauthorized")
            .build()
        val authRequest = authenticator.authenticate(route, response)

        assertThat(authRequest!!.header("Authorization"))
            .isEqualTo("Basic ${RecordingAuthenticator.BASE_64_CREDENTIALS}")
    }

    @Test
    fun testBasicAuthForProxy() {
        fakeDns["server"] = listOf(InetAddress.getLocalHost())

        val proxy = Proxy.http(InetSocketAddress(0))
        val address = factory.newAddress(proxy = proxy)
        val route = factory.newRoute(address = address)

        val request = ClientRequest.builder()
            .url("https://server/robots.txt")
            .get()
        val response = ClientResponse.builder()
            .request(request)
            .statusCode(407)
            .header("Proxy-Authenticate", "Basic realm=\"User Visible Realm\"")
            .protocol(HTTP_2)
            .statusMessage("Unauthorized")
            .build()
        val authRequest = authenticator.authenticate(route, response)

        assertThat(authRequest!!.header("Proxy-Authorization"))
            .isEqualTo("Basic ${RecordingAuthenticator.BASE_64_CREDENTIALS}")
    }

    @Test
    fun noSupportForNonBasicAuth() {
        val request = ClientRequest.builder()
            .url("https://server/robots.txt")
            .get()

        val response = ClientResponse.builder()
            .request(request)
            .statusCode(401)
            .header("WWW-Authenticate", "UnsupportedScheme realm=\"User Visible Realm\"")
            .protocol(HTTP_2)
            .statusMessage("Unauthorized")
            .build()

        val authRequest = authenticator.authenticate(null, response)
        assertThat(authRequest).isNull()
    }
}
