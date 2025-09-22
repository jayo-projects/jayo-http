/*
 * Copyright (c) 2025-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package jayo.http.internal.authentication

import jayo.http.Authenticator.JAYO_PREEMPTIVE_CHALLENGE
import jayo.http.ClientRequest
import jayo.http.ClientResponse
import jayo.http.internal.RecordingAuthenticator
import jayo.http.internal.connection.TestValueFactory
import jayo.network.Proxy
import jayo.tls.Protocol.HTTP_2
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class DefaultProxyAuthenticatorTest {
    private var authenticator = DefaultProxyAuthenticator.INSTANCE
    private val factory = TestValueFactory()

    @AfterEach
    fun tearDown() {
        factory.close()
    }

    @Test
    fun testPreemptiveAuthForProxy() {
        val proxy = Proxy.http(
            InetSocketAddress(0),
            RecordingAuthenticator.USERNAME,
            RecordingAuthenticator.PASSWORD
        )
        val address = factory.newAddress(proxy = proxy)
        val route = factory.newRoute(address = address)

        val request = ClientRequest.builder()
            .url("https://server/robots.txt")
            .get()
        val response = ClientResponse.builder()
            .request(request)
            .code(407)
            .header("Proxy-Authenticate", JAYO_PREEMPTIVE_CHALLENGE)
            .protocol(HTTP_2)
            .message("Preemptive Authenticate")
            .build()
        val authRequest = authenticator.authenticate(route, response)

        assertThat(authRequest!!.header("Proxy-Authorization"))
            .isEqualTo("Basic ${RecordingAuthenticator.BASE_64_CREDENTIALS}")
    }

    @Test
    fun testBasicAuthForProxy() {
        val proxy = Proxy.http(
            InetSocketAddress(0),
            RecordingAuthenticator.USERNAME,
            RecordingAuthenticator.PASSWORD
        )
        val address = factory.newAddress(proxy = proxy)
        val route = factory.newRoute(address = address)

        val request = ClientRequest.builder()
            .url("https://server/robots.txt")
            .get()
        val response = ClientResponse.builder()
            .request(request)
            .code(407)
            .header("Proxy-Authenticate", "Basic realm=\"User Visible Realm\"")
            .protocol(HTTP_2)
            .message("Unauthorized")
            .build()
        val authRequest = authenticator.authenticate(route, response)

        assertThat(authRequest!!.header("Proxy-Authorization"))
            .isEqualTo("Basic ${RecordingAuthenticator.BASE_64_CREDENTIALS}")
    }

    @Test
    fun testBasicAuthForProxyWithoutAuth() {
        val proxy = Proxy.http(InetSocketAddress(0))
        val address = factory.newAddress(proxy = proxy)
        val route = factory.newRoute(address = address)

        val request = ClientRequest.builder()
            .url("https://server/robots.txt")
            .get()
        val response = ClientResponse.builder()
            .request(request)
            .code(407)
            .header("Proxy-Authenticate", "Basic realm=\"User Visible Realm\"")
            .protocol(HTTP_2)
            .message("Unauthorized")
            .build()
        val authRequest = authenticator.authenticate(route, response)

        assertThat(authRequest).isNull()
    }

    @Test
    fun noSupportForNonBasicAuth() {
        val proxy = Proxy.http(
            InetSocketAddress(0),
            RecordingAuthenticator.USERNAME,
            RecordingAuthenticator.PASSWORD
        )
        val address = factory.newAddress(proxy = proxy)
        val route = factory.newRoute(address = address)
        val request = ClientRequest.builder()
            .url("https://server/robots.txt")
            .get()

        val response = ClientResponse.builder()
            .request(request)
            .code(407)
            .header("Proxy-Authenticate", "NotBasic realm=\"User Visible Realm\"")
            .protocol(HTTP_2)
            .message("Unauthorized")
            .build()

        val authRequest = authenticator.authenticate(route, response)
        assertThat(authRequest).isNull()
    }
}
