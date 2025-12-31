/*
 * Copyright (c) 2026-present, pull-vert and Jayo contributors.
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

package jayo.http.recipes.kt

import jayo.JayoException
import jayo.http.ClientRequest
import jayo.http.HttpUrl
import jayo.http.JayoHttpClient
import jayo.tls.ClientHandshakeCertificates
import jayo.tls.ClientTlsSocket
import jayo.tls.HeldCertificate
import jayo.tls.ServerHandshakeCertificates
import jayo.tools.JayoTlsUtils
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf

class DevServer {
    fun run() {
        val localhostCertificate: HeldCertificate = HeldCertificate.builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates: ServerHandshakeCertificates =
            ServerHandshakeCertificates.builder(localhostCertificate).build()

        MockWebServer().use { server ->
            server.start()
            val sslSocketFactory = JayoTlsUtils.handshakeCertSSLContext(serverCertificates).socketFactory
            server.useHttps(sslSocketFactory)
            server.enqueue(MockResponse(200, headersOf(), ""))

            val clientCertificates = ClientHandshakeCertificates.builder()
                .addTrustedCertificate(localhostCertificate.getCertificate())
                .build()

            val client = JayoHttpClient.builder()
                .tlsConfig(ClientTlsSocket.builder(clientCertificates))
                .build()

            val request = ClientRequest.builder()
                .url(HttpUrl.get(server.url("/").toString()))
                .get()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw JayoException("Unexpected code ${response.statusCode}")
                }

                println(response.request.url)
            }
        }
    }
}

fun main() {
    DevServer().run()
}
