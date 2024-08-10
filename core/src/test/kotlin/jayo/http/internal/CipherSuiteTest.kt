/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jayo.http.internal

import jayo.http.DelegatingSSLEngine
import jayo.tls.TlsVersion
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

class CipherSuiteTest {
    @Test
    fun applyIntersectionRetainsTlsPrefixes() {
        val sslEngine = FakeSslEngine()
        sslEngine.enabledProtocols = arrayOf("TLSv1")
        sslEngine.supportedCipherSuites = arrayOf("SSL_A", "SSL_B", "SSL_C", "SSL_D", "SSL_E")
        sslEngine.enabledCipherSuites = arrayOf("SSL_A", "SSL_B", "SSL_C")
        val connectionSpec =
            RealConnectionSpec.Builder(true)
                .tlsVersions(TlsVersion.TLS_1_0)
                .cipherSuites("TLS_A", "TLS_C", "TLS_E")
                .build()
        connectionSpec.apply(sslEngine, false)
        assertArrayEquals(arrayOf("TLS_A", "TLS_C"), sslEngine.enabledCipherSuites)
    }

    @Test
    fun applyIntersectionRetainsSslPrefixes() {
        val sslEngine = FakeSslEngine()
        sslEngine.enabledProtocols = arrayOf("TLSv1")
        sslEngine.supportedCipherSuites =
            arrayOf("TLS_A", "TLS_B", "TLS_C", "TLS_D", "TLS_E")
        sslEngine.enabledCipherSuites = arrayOf("TLS_A", "TLS_B", "TLS_C")
        val connectionSpec =
            RealConnectionSpec.Builder(true)
                .tlsVersions(TlsVersion.TLS_1_0)
                .cipherSuites("SSL_A", "SSL_C", "SSL_E")
                .build()
        connectionSpec.apply(sslEngine, false)
        assertArrayEquals(arrayOf("SSL_A", "SSL_C"), sslEngine.enabledCipherSuites)
    }

    @Test
    fun applyIntersectionAddsSslScsvForFallback() {
        val sslEngine = FakeSslEngine()
        sslEngine.enabledProtocols = arrayOf("TLSv1")
        sslEngine.supportedCipherSuites = arrayOf("SSL_A", "SSL_FALLBACK_SCSV")
        sslEngine.enabledCipherSuites = arrayOf("SSL_A")
        val connectionSpec =
            RealConnectionSpec.Builder(true)
                .tlsVersions(TlsVersion.TLS_1_0)
                .cipherSuites("SSL_A")
                .build()
        connectionSpec.apply(sslEngine, true)
        assertArrayEquals(
            arrayOf("SSL_A", "SSL_FALLBACK_SCSV"),
            sslEngine.enabledCipherSuites,
        )
    }

    @Test
    fun applyIntersectionAddsTlsScsvForFallback() {
        val sslEngine = FakeSslEngine()
        sslEngine.enabledProtocols = arrayOf("TLSv1")
        sslEngine.supportedCipherSuites = arrayOf("TLS_A", "TLS_FALLBACK_SCSV")
        sslEngine.enabledCipherSuites = arrayOf("TLS_A")
        val connectionSpec =
            RealConnectionSpec.Builder(true)
                .tlsVersions(TlsVersion.TLS_1_0)
                .cipherSuites("TLS_A")
                .build()
        connectionSpec.apply(sslEngine, true)
        assertArrayEquals(
            arrayOf("TLS_A", "TLS_FALLBACK_SCSV"),
            sslEngine.enabledCipherSuites,
        )
    }

    @Test
    fun applyIntersectionToProtocolVersion() {
        val sslEngine = FakeSslEngine()
        sslEngine.enabledProtocols = arrayOf("TLSv1", "TLSv1.1", "TLSv1.2")
        sslEngine.supportedCipherSuites = arrayOf("TLS_A")
        sslEngine.enabledCipherSuites = arrayOf("TLS_A")
        val connectionSpec =
            RealConnectionSpec.Builder(true)
                .tlsVersions(TlsVersion.TLS_1_1, TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
                .cipherSuites("TLS_A")
                .build()
        connectionSpec.apply(sslEngine, false)
        assertArrayEquals(arrayOf("TLSv1.1", "TLSv1.2"), sslEngine.enabledProtocols)
    }

    internal class FakeSslEngine : DelegatingSSLEngine(null) {
        private lateinit var enabledProtocols: Array<String>
        private lateinit var supportedCipherSuites: Array<String>
        private lateinit var enabledCipherSuites: Array<String>

        override fun getEnabledProtocols(): Array<String> {
            return enabledProtocols
        }

        override fun setEnabledProtocols(protocols: Array<String>) {
            this.enabledProtocols = protocols
        }

        override fun getSupportedCipherSuites(): Array<String> {
            return supportedCipherSuites
        }

        fun setSupportedCipherSuites(supportedCipherSuites: Array<String>) {
            this.supportedCipherSuites = supportedCipherSuites
        }

        override fun getEnabledCipherSuites(): Array<String> {
            return enabledCipherSuites
        }

        override fun setEnabledCipherSuites(suites: Array<String>) {
            this.enabledCipherSuites = suites
        }
    }
}
