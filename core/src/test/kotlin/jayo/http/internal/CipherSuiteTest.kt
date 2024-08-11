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

import jayo.http.CipherSuite
import jayo.http.CipherSuite.fromJavaName
import jayo.http.TlsVersion
import jayo.http.testing.DelegatingSSLEngine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

class CipherSuiteTest {
    @Test
    fun hashCode_usesIdentityHashCode_legacyCase() {
        val cs = CipherSuite.TLS_RSA_EXPORT_WITH_RC4_40_MD5 // This one's javaName starts with "SSL_".
        assertThat(cs.hashCode()).isEqualTo(System.identityHashCode(cs))
    }

    @Test
    fun hashCode_usesIdentityHashCode_regularCase() {
        // This one's javaName matches the identifier.
        val cs = CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA256
        assertThat(cs.hashCode()).isEqualTo(System.identityHashCode(cs))
    }

    @Test
    fun instancesAreInterned() {
        assertThat(fromJavaName("TestCipherSuite"))
            .isSameAs(fromJavaName("TestCipherSuite"))
        assertThat(fromJavaName(CipherSuite.TLS_KRB5_WITH_DES_CBC_MD5.javaName))
            .isSameAs(CipherSuite.TLS_KRB5_WITH_DES_CBC_MD5)
    }

    /**
     * Tests that interned CipherSuite instances remain the case across garbage collections, even if
     * the String used to construct them is no longer strongly referenced outside of the CipherSuite.
     */
    @Test
    fun instancesAreInterned_survivesGarbageCollection() {
        // We're not holding onto a reference to this String instance outside of the CipherSuite...
        val cs = fromJavaName("FakeCipherSuite_instancesAreInterned")
        System.gc() // Unless cs references the String instance, it may now be garbage collected.
        assertThat(fromJavaName(java.lang.String(cs.javaName) as String))
            .isSameAs(cs)
    }

    @Test
    fun equals() {
        assertThat(fromJavaName("cipher"))
            .isEqualTo(fromJavaName("cipher"))
        assertThat(fromJavaName("cipherB"))
            .isNotEqualTo(fromJavaName("cipherA"))
        assertThat(CipherSuite.TLS_RSA_EXPORT_WITH_RC4_40_MD5)
            .isEqualTo(fromJavaName("SSL_RSA_EXPORT_WITH_RC4_40_MD5"))
        assertThat(CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA256)
            .isNotEqualTo(CipherSuite.TLS_RSA_EXPORT_WITH_RC4_40_MD5)
    }

    @Test
    fun fromJavaName_acceptsArbitraryStrings() {
        // Shouldn't throw.
        fromJavaName("example CipherSuite name that is not in the whitelist")
    }

    @Test
    fun javaName_examples() {
        assertThat(CipherSuite.TLS_RSA_EXPORT_WITH_RC4_40_MD5.javaName)
            .isEqualTo("SSL_RSA_EXPORT_WITH_RC4_40_MD5")
        assertThat(CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA256.javaName)
            .isEqualTo("TLS_RSA_WITH_AES_128_CBC_SHA256")
        assertThat(fromJavaName("TestCipherSuite").javaName)
            .isEqualTo("TestCipherSuite")
    }

    @Test
    fun javaName_equalsToString() {
        assertThat(CipherSuite.TLS_RSA_EXPORT_WITH_RC4_40_MD5.toString())
            .isEqualTo(CipherSuite.TLS_RSA_EXPORT_WITH_RC4_40_MD5.javaName)
        assertThat(CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA256.toString())
            .isEqualTo(CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA256.javaName)
    }

    /**
     * On the Oracle JVM some older cipher suites have the "SSL_" prefix and others have the "TLS_"
     * prefix. On the IBM JVM all cipher suites have the "SSL_" prefix.
     *
     * Prior to OkHttp 3.3.1 we accepted either form and consider them equivalent. And since OkHttp
     * 3.7.0 this is also true. But OkHttp 3.3.1 through 3.6.0 treated these as different.
     */
    @Test
    fun fromJavaName_fromLegacyEnumName() {
        // These would have been considered equal in OkHttp 3.3.1, but now aren't.
        assertThat(fromJavaName("SSL_RSA_EXPORT_WITH_RC4_40_MD5"))
            .isEqualTo(fromJavaName("TLS_RSA_EXPORT_WITH_RC4_40_MD5"))
        assertThat(fromJavaName("SSL_DH_RSA_EXPORT_WITH_DES40_CBC_SHA"))
            .isEqualTo(fromJavaName("TLS_DH_RSA_EXPORT_WITH_DES40_CBC_SHA"))
        assertThat(fromJavaName("SSL_FAKE_NEW_CIPHER"))
            .isEqualTo(fromJavaName("TLS_FAKE_NEW_CIPHER"))
    }

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
