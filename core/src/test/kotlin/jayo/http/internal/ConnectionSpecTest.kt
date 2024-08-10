/*
 * Copyright (c) 2024-present, pull-vert and Jayo contributors.
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

import jayo.http.ConnectionSpec
import jayo.tls.CipherSuite
import jayo.tls.PlatformRule
import jayo.tls.TlsVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.CopyOnWriteArraySet
import javax.net.ssl.SSLContext
import kotlin.test.assertFailsWith

class ConnectionSpecTest {
    @RegisterExtension
    val platform = PlatformRule()

    @Test
    fun noTlsVersions() {
        assertFailsWith<IllegalArgumentException> {
            ConnectionSpec.builder(ConnectionSpec.COMPATIBLE_TLS)
                .tlsVersions(*arrayOf<String>())
                .build()
        }.also { expected ->
            assertThat(expected.message)
                .isEqualTo("At least one TLS version is required")
        }
    }

    @Test
    fun noCipherSuites() {
        assertFailsWith<IllegalArgumentException> {
            ConnectionSpec.builder(ConnectionSpec.COMPATIBLE_TLS)
                .cipherSuites(*arrayOf<CipherSuite>())
                .build()
        }.also { expected ->
            assertThat(expected.message)
                .isEqualTo("At least one cipher suite is required")
        }
    }

    @Test
    fun cleartextBuilder() {
        val cleartextSpec = RealConnectionSpec.Builder(false).build()
        assertThat(cleartextSpec.isTls).isFalse()
    }

    @Test
    fun tlsBuilder_explicitCiphers() {
        val tlsSpec =
            RealConnectionSpec.Builder(true)
                .cipherSuites(CipherSuite.TLS_RSA_WITH_RC4_128_MD5)
                .tlsVersions(TlsVersion.TLS_1_2)
                .build()
        assertThat(tlsSpec.cipherSuites!!.toList())
            .containsExactly(CipherSuite.TLS_RSA_WITH_RC4_128_MD5)
        assertThat(tlsSpec.tlsVersions!!.toList())
            .containsExactly(TlsVersion.TLS_1_2)
    }

    @Test
    fun tlsBuilder_defaultCiphers() {
        val tlsSpec =
            RealConnectionSpec.Builder(true)
                .tlsVersions(TlsVersion.TLS_1_2)
                .build()
        assertThat(tlsSpec.cipherSuites).isNull()
        assertThat(tlsSpec.tlsVersions!!.toList())
            .containsExactly(TlsVersion.TLS_1_2)
    }

    @Test
    fun tls_defaultCiphers_noFallbackIndicator() {
        platform.assumeNotConscrypt()
        platform.assumeNotBouncyCastle()
        val tlsSpec =
            RealConnectionSpec.Builder(true)
                .tlsVersions(TlsVersion.TLS_1_2)
                .build()
        val sslEngine = SSLContext.getDefault().createSSLEngine()
        sslEngine.enabledCipherSuites =
            arrayOf(
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256.javaName,
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA.javaName,
            )
        sslEngine.enabledProtocols =
            arrayOf(
                TlsVersion.TLS_1_2.javaName,
                TlsVersion.TLS_1_1.javaName,
            )
        assertThat(tlsSpec.isCompatible(sslEngine)).isTrue()
        tlsSpec.apply(sslEngine, false)
        assertThat(sslEngine.enabledProtocols).containsExactly(
            TlsVersion.TLS_1_2.javaName,
        )
        assertThat(sslEngine.enabledCipherSuites.toList())
            .containsExactlyInAnyOrder(
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256.javaName,
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA.javaName,
            )
    }

    @Test
    fun tls_defaultCiphers_withFallbackIndicator() {
        platform.assumeNotConscrypt()
        platform.assumeNotBouncyCastle()
        val tlsSpec =
            RealConnectionSpec.Builder(true)
                .tlsVersions(TlsVersion.TLS_1_2)
                .build()
        val sslEngine = SSLContext.getDefault().createSSLEngine()
        sslEngine.enabledCipherSuites =
            arrayOf(
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256.javaName,
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA.javaName,
            )
        sslEngine.enabledProtocols =
            arrayOf(
                TlsVersion.TLS_1_2.javaName,
                TlsVersion.TLS_1_1.javaName,
            )
        assertThat(tlsSpec.isCompatible(sslEngine)).isTrue()
        tlsSpec.apply(sslEngine, true)
        assertThat(sslEngine.enabledProtocols).containsExactly(
            TlsVersion.TLS_1_2.javaName,
        )
        val expectedCipherSuites: MutableList<String> = ArrayList()
        expectedCipherSuites.add(CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256.javaName)
        expectedCipherSuites.add(CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA.javaName)
        if (listOf<String>(*sslEngine.supportedCipherSuites).contains("TLS_FALLBACK_SCSV")) {
            expectedCipherSuites.add("TLS_FALLBACK_SCSV")
        }
        assertThat(sslEngine.enabledCipherSuites)
            .containsExactly(*expectedCipherSuites.toTypedArray())
    }

    @Test
    fun tls_explicitCiphers() {
        platform.assumeNotConscrypt()
        platform.assumeNotBouncyCastle()
        val tlsSpec =
            RealConnectionSpec.Builder(true)
                .cipherSuites(CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256)
                .tlsVersions(TlsVersion.TLS_1_2)
                .build()
        val sslEngine = SSLContext.getDefault().createSSLEngine()
        sslEngine.enabledCipherSuites =
            arrayOf(
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256.javaName,
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA.javaName,
            )
        sslEngine.enabledProtocols =
            arrayOf(
                TlsVersion.TLS_1_2.javaName,
                TlsVersion.TLS_1_1.javaName,
            )
        assertThat(tlsSpec.isCompatible(sslEngine)).isTrue()
        tlsSpec.apply(sslEngine, true)
        assertThat(sslEngine.enabledProtocols).containsExactly(
            TlsVersion.TLS_1_2.javaName,
        )
        val expectedCipherSuites: MutableList<String> = ArrayList()
        expectedCipherSuites.add(CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256.javaName)
        if (listOf<String>(*sslEngine.supportedCipherSuites).contains("TLS_FALLBACK_SCSV")) {
            expectedCipherSuites.add("TLS_FALLBACK_SCSV")
        }
        assertThat(sslEngine.enabledCipherSuites)
            .containsExactly(*expectedCipherSuites.toTypedArray())
    }

    @Test
    fun tls_stringCiphersAndVersions() {
        // Supporting arbitrary input strings allows users to enable suites and versions that are not yet known to the
        // library, but are supported by the platform.
        ConnectionSpec.builder(ConnectionSpec.COMPATIBLE_TLS)
            .cipherSuites("MAGIC-CIPHER")
            .tlsVersions("TLS9k")
            .build()
    }

    @Test
    fun tls_missingRequiredCipher() {
        platform.assumeNotConscrypt()
        platform.assumeNotBouncyCastle()
        val tlsSpec =
            RealConnectionSpec.Builder(true)
                .cipherSuites(CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256)
                .tlsVersions(TlsVersion.TLS_1_2)
                .build()
        val sslEngine = SSLContext.getDefault().createSSLEngine()
        sslEngine.enabledProtocols =
            arrayOf(
                TlsVersion.TLS_1_2.javaName,
                TlsVersion.TLS_1_1.javaName,
            )
        sslEngine.enabledCipherSuites =
            arrayOf(
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256.javaName,
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA.javaName,
            )
        assertThat(tlsSpec.isCompatible(sslEngine)).isTrue()
        sslEngine.enabledCipherSuites =
            arrayOf(
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA.javaName,
            )
        assertThat(tlsSpec.isCompatible(sslEngine)).isFalse()
    }

    @Test
    fun allEnabledCipherSuites() {
        platform.assumeNotConscrypt()
        platform.assumeNotBouncyCastle()
        val tlsSpec =
            RealConnectionSpec.Builder(RealConnectionSpec.COMPATIBLE_TLS)
                .enableAllCipherSuites(true)
                .build()
        assertThat(tlsSpec.cipherSuites).isNull()
        val sslEngine = SSLContext.getDefault().createSSLEngine()
        sslEngine.enabledCipherSuites =
            arrayOf(
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256.javaName,
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA.javaName,
            )
        tlsSpec.apply(sslEngine, false)

        assertThat(sslEngine.enabledCipherSuites)
            .containsExactly(
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256.javaName,
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA.javaName,
            )
    }

    @Test
    fun allEnabledTlsVersions() {
        platform.assumeNotConscrypt()
        val tlsSpec =
            RealConnectionSpec.Builder(RealConnectionSpec.COMPATIBLE_TLS)
                .enableAllTlsVersions(true)
                .build()
        assertThat(tlsSpec.tlsVersions).isNull()
        val sslEngine = SSLContext.getDefault().createSSLEngine()
        sslEngine.enabledProtocols =
            arrayOf(
                TlsVersion.SSL_3_0.javaName,
                TlsVersion.TLS_1_1.javaName,
                TlsVersion.TLS_1_2.javaName,
                TlsVersion.TLS_1_3.javaName,
            )
        tlsSpec.apply(sslEngine, false)
        assertThat(sslEngine.enabledProtocols)
            .containsExactly(
                TlsVersion.SSL_3_0.javaName,
                TlsVersion.TLS_1_1.javaName,
                TlsVersion.TLS_1_2.javaName,
                TlsVersion.TLS_1_3.javaName,
            )
    }

    @Test
    fun tls_missingTlsVersion() {
        platform.assumeNotConscrypt()
        platform.assumeNotBouncyCastle()
        val tlsSpec =
            RealConnectionSpec.Builder(true)
                .cipherSuites(CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256)
                .tlsVersions(TlsVersion.TLS_1_2)
                .build()
        val sslEngine = SSLContext.getDefault().createSSLEngine()
        sslEngine.enabledCipherSuites =
            arrayOf(
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256.javaName,
            )
        sslEngine.enabledProtocols =
            arrayOf(
                TlsVersion.TLS_1_2.javaName,
                TlsVersion.TLS_1_1.javaName,
            )
        assertThat(tlsSpec.isCompatible(sslEngine)).isTrue()
        sslEngine.enabledProtocols = arrayOf(TlsVersion.TLS_1_1.javaName)
        assertThat(tlsSpec.isCompatible(sslEngine)).isFalse()
    }

    @Test
    fun equalsAndHashCode() {
        val allCipherSuites =
            ConnectionSpec.builder(ConnectionSpec.COMPATIBLE_TLS)
                .enableAllCipherSuites(true)
                .build()
        val allTlsVersions =
            ConnectionSpec.builder(ConnectionSpec.COMPATIBLE_TLS)
                .enableAllTlsVersions(true)
                .build()
        val set: MutableSet<Any> = CopyOnWriteArraySet()
        assertThat(set.add(ConnectionSpec.COMPATIBLE_TLS)).isTrue()
        assertThat(set.add(ConnectionSpec.LEGACY_TLS)).isTrue()
        assertThat(set.add(ConnectionSpec.CLEARTEXT)).isTrue()
        assertThat(set.add(allTlsVersions)).isTrue()
        assertThat(set.add(allCipherSuites)).isTrue()
        allCipherSuites.hashCode()
        assertThat(allCipherSuites.equals(null)).isFalse()
        assertThat(set.remove(ConnectionSpec.COMPATIBLE_TLS)).isTrue()
        assertThat(set.remove(ConnectionSpec.LEGACY_TLS))
            .isTrue()
        assertThat(set.remove(ConnectionSpec.CLEARTEXT)).isTrue()
        assertThat(set.remove(allTlsVersions)).isTrue()
        assertThat(set.remove(allCipherSuites)).isTrue()
        assertThat(set).isEmpty()
        allTlsVersions.hashCode()
        assertThat(allTlsVersions.equals(null)).isFalse()
    }

    @Test
    fun allEnabledToString() {
        val connectionSpec =
            ConnectionSpec.builder(ConnectionSpec.COMPATIBLE_TLS)
                .enableAllTlsVersions(true)
                .enableAllCipherSuites(true)
                .build()
        assertThat(connectionSpec.toString()).isEqualTo(
            "ConnectionSpec(cipherSuites=[all enabled], tlsVersions=[all enabled])",
        )
    }

    @Test
    fun simpleToString() {
        val connectionSpec =
            ConnectionSpec.builder(ConnectionSpec.COMPATIBLE_TLS)
                .tlsVersions(TlsVersion.TLS_1_2)
                .cipherSuites(CipherSuite.TLS_RSA_WITH_RC4_128_MD5)
                .build()
        assertThat(connectionSpec.toString()).isEqualTo(
            "ConnectionSpec(cipherSuites=[SSL_RSA_WITH_RC4_128_MD5], tlsVersions=[TLSv1.2])",
        )
    }
}
