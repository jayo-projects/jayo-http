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
import jayo.http.internal.RecordingJayoAuthenticator
import jayo.network.NetworkServer
import jayo.network.NetworkSocket
import jayo.network.Proxy
import jayo.scheduler.TaskRunner
import jayo.scheduler.internal.TaskFaker
import jayo.tls.ClientHandshakeCertificates
import jayo.tls.ClientTlsSocket
import jayo.tls.Protocol
import jayo.tools.JayoTlsUtils.localhost
import java.io.Closeable
import java.net.InetSocketAddress
import java.time.Duration
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Jayo HTTP is usually tested with functional tests: these use public APIs to confirm behavior against MockWebServer.
 * In cases where logic is particularly tricky, we use unit tests. This class makes it easy to get sample values to use
 * in such tests.
 *
 * This class is pretty fast and loose with default values: it attempts to provide values that are well-formed but
 * doesn't guarantee values are internally consistent. Callers must take care to configure the factory when sample
 * values impact the correctness of the test.
 */
class TestValueFactory : Closeable {
    var taskFaker: TaskFaker = TaskFaker()
    var taskRunner: TaskRunner = taskFaker.taskRunner
    var dns: Dns = Dns.SYSTEM
    var proxyAuthenticator: Authenticator = RecordingJayoAuthenticator("password", null)
    var connectionSpecs: List<ConnectionSpec> =
        listOf(
            ConnectionSpec.MODERN_TLS,
            ConnectionSpec.COMPATIBLE_TLS,
            ConnectionSpec.CLEARTEXT,
        )
    var protocols: List<Protocol> = listOf(Protocol.HTTP_1_1)
    var handshakeCertificates: ClientHandshakeCertificates = localhost()
    var tlsClientSocketBuilder: ClientTlsSocket.Builder = ClientTlsSocket.builder(handshakeCertificates)
    var hostnameVerifier: HostnameVerifier? = HttpsURLConnection.getDefaultHostnameVerifier()
    var uriHost: String = "example.com"
    var uriPort: Int = 1
    var networkServer: NetworkServer? = null

    fun newConnection(
        pool: RealConnectionPool,
        route: Route,
        idleAtNanos: Long = Long.MAX_VALUE,
        taskRunner: TaskRunner = this.taskRunner,
    ): RealConnection {
        networkServer = NetworkServer.bindTcp(InetSocketAddress(0  /* find free port */))
        thread {
            networkServer!!.accept()
        }
        val result =
            RealConnection.newTestConnection(
                taskRunner,
                route,
                NetworkSocket.connectTcp(networkServer!!.localAddress),
                idleAtNanos,
            )
        result.lock.withLock { pool.put(result) }
        return result
    }

    fun newConnectionPool(
        taskRunner: TaskRunner = this.taskRunner,
        maxIdleConnections: Int = Int.MAX_VALUE,
    ): RealConnectionPool =
        RealConnectionPool(
            taskRunner,
            maxIdleConnections,
            Duration.ofNanos(100L),
        )

    /** Returns an address that's without a TLS socket builder or hostname verifier.  */
    fun newAddress(
        uriHost: String = this.uriHost,
        uriPort: Int = this.uriPort,
        proxy: Proxy? = null,
    ): Address =
        RealAddress(
            uriHost,
            uriPort,
            dns,
            NetworkSocket.builder(),
            null,
            null,
            null,
            protocols,
            connectionSpecs,
            proxy,
            proxyAuthenticator
        )

    fun newHttpsAddress(
        uriHost: String = this.uriHost,
        uriPort: Int = this.uriPort,
        proxy: Proxy? = null,
        tlsClientSocketBuilder: ClientTlsSocket.Builder? = this.tlsClientSocketBuilder,
        hostnameVerifier: HostnameVerifier? = this.hostnameVerifier,
    ): Address =
        RealAddress(
            uriHost,
            uriPort,
            dns,
            NetworkSocket.builder(),
            tlsClientSocketBuilder,
            hostnameVerifier,
            null,
            protocols,
            connectionSpecs,
            proxy,
            proxyAuthenticator
        )

    fun newRoute(
        address: Address = newAddress(),
        socketAddress: InetSocketAddress = InetSocketAddress.createUnresolved(uriHost, uriPort),
    ): Route =
        RealRoute(address, socketAddress)

    fun newChain(call: RealCall): RealInterceptorChain =
        RealInterceptorChain(
            call,
            listOf(),
            0,
            null,
            call.request(),
        )

    fun newRoutePlanner(
        client: JayoHttpClient,
        address: Address = newAddress(),
    ): RoutePlanner {
        val call = RealCall(
            client as RealJayoHttpClient,
            ClientRequest.get(address.url),
            false,
        )
        val chain = newChain(call)
        return RealRoutePlanner(
            client.taskRunner,
            client.connectionPool as RealConnectionPool,
            client.readTimeout,
            client.writeTimeout,
            client.pingIntervalMillis,
            client.retryOnConnectionFailure(),
            client.fastFallback(),
            address,
            client.routeDatabase,
            CallConnectionUser(call, chain),
        )
    }

    override fun close() {
        taskFaker.close()
        networkServer?.close()
    }
}
