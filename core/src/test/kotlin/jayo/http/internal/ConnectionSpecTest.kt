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

import io.mockk.every
import io.mockk.mockk
import jayo.http.ConnectionSpec
import jayo.tls.CipherSuite
import jayo.tls.ClientTlsSocket
import jayo.tls.TlsVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.test.assertFailsWith

class ConnectionSpecTest {
    @Test
    fun noTlsVersions() {
        assertFailsWith<IllegalArgumentException> {
            ConnectionSpec.builder(ConnectionSpec.COMPATIBLE_TLS)
                .tlsVersions(listOf())
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
                .cipherSuites(listOf())
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
                .cipherSuites(listOf(CipherSuite.TLS_RSA_WITH_RC4_128_MD5))
                .tlsVersions(listOf(TlsVersion.TLS_1_2))
                .build()
        assertThat(tlsSpec.cipherSuites)
            .containsExactly(CipherSuite.TLS_RSA_WITH_RC4_128_MD5)
        assertThat(tlsSpec.tlsVersions)
            .containsExactly(TlsVersion.TLS_1_2)
    }

    @Test
    fun tlsBuilder_defaultCiphers() {
        val tlsSpec =
            RealConnectionSpec.Builder(true)
                .tlsVersions(listOf(TlsVersion.TLS_1_2))
                .build()
        assertThat(tlsSpec.cipherSuites).isNull()
        assertThat(tlsSpec.tlsVersions)
            .containsExactly(TlsVersion.TLS_1_2)
    }

    @Test
    fun applyIntersectionToCiphers() {
        val tlsParameterizer = mockk<ClientTlsSocket.Parameterizer>(relaxed = true)
        every { tlsParameterizer.enabledTlsVersions } returns listOf(TlsVersion.TLS_1_0)
        every { tlsParameterizer.supportedCipherSuites } returns
                listOf(
                    CipherSuite.TLS_AES_128_GCM_SHA256,
                    CipherSuite.TLS_AES_128_CCM_SHA256,
                    CipherSuite.TLS_AES_128_CCM_8_SHA256,
                    CipherSuite.TLS_AES_256_GCM_SHA384,
                )
        every { tlsParameterizer.enabledCipherSuites } returns
                listOf(
                    CipherSuite.TLS_AES_128_GCM_SHA256,
                    CipherSuite.TLS_AES_128_CCM_SHA256,
                    CipherSuite.TLS_AES_128_CCM_8_SHA256,
                )
        var connectionSpec =
            RealConnectionSpec.Builder(true)
                .tlsVersions(listOf(TlsVersion.TLS_1_0))
                .cipherSuites(listOf(CipherSuite.TLS_AES_128_CCM_SHA256, CipherSuite.TLS_AES_256_GCM_SHA384))
                .build()

        assertThat(connectionSpec.isCompatible(tlsParameterizer)).isTrue()
        connectionSpec = connectionSpec.supportedSpec(tlsParameterizer, false)

        assertThat(connectionSpec.cipherSuites).containsExactly(CipherSuite.TLS_AES_128_CCM_SHA256)
    }

    @Test
    fun applyIntersectionToCiphersAddsTlsScsvForFallback() {
        val tlsParameterizer = mockk<ClientTlsSocket.Parameterizer>(relaxed = true)
        every { tlsParameterizer.enabledTlsVersions } returns listOf(TlsVersion.TLS_1_0)
        every { tlsParameterizer.supportedCipherSuites } returns
                listOf(
                    CipherSuite.TLS_AES_128_GCM_SHA256,
                    CipherSuite.TLS_FALLBACK_SCSV,
                )
        every { tlsParameterizer.enabledCipherSuites } returns listOf(CipherSuite.TLS_AES_128_GCM_SHA256)
        var connectionSpec =
            RealConnectionSpec.Builder(true)
                .tlsVersions(listOf(TlsVersion.TLS_1_0))
                .cipherSuites(listOf(CipherSuite.TLS_AES_128_GCM_SHA256))
                .build()

        assertThat(connectionSpec.isCompatible(tlsParameterizer)).isTrue()
        connectionSpec = connectionSpec.supportedSpec(tlsParameterizer, true)

        assertThat(connectionSpec.cipherSuites)
            .containsExactly(CipherSuite.TLS_AES_128_GCM_SHA256, CipherSuite.TLS_FALLBACK_SCSV)
    }

    @Test
    fun applyIntersectionToTlsVersions() {
        val tlsParameterizer = mockk<ClientTlsSocket.Parameterizer>(relaxed = true)
        every { tlsParameterizer.enabledTlsVersions } returns
                listOf(TlsVersion.TLS_1_1, TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
        every { tlsParameterizer.supportedCipherSuites } returns listOf(CipherSuite.TLS_AES_128_GCM_SHA256)
        every { tlsParameterizer.enabledCipherSuites } returns listOf(CipherSuite.TLS_AES_128_GCM_SHA256)
        var connectionSpec =
            RealConnectionSpec.Builder(true)
                .tlsVersions(listOf(TlsVersion.TLS_1_0, TlsVersion.TLS_1_2, TlsVersion.TLS_1_3))
                .cipherSuites(listOf(CipherSuite.TLS_AES_128_GCM_SHA256))
                .build()

        assertThat(connectionSpec.isCompatible(tlsParameterizer)).isTrue()
        connectionSpec = connectionSpec.supportedSpec(tlsParameterizer, false)

        assertThat(connectionSpec.tlsVersions).containsExactly(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
    }

    @Test
    fun tls_missingRequiredCipher() {
        val tlsParameterizer = mockk<ClientTlsSocket.Parameterizer>(relaxed = true)
        every { tlsParameterizer.enabledTlsVersions } returns listOf(TlsVersion.TLS_1_0)
        every { tlsParameterizer.supportedCipherSuites } returns
                listOf(
                    CipherSuite.TLS_AES_128_GCM_SHA256,
                    CipherSuite.TLS_AES_128_CCM_SHA256,
                )
        every { tlsParameterizer.enabledCipherSuites } returns listOf(CipherSuite.TLS_AES_128_GCM_SHA256)
        val connectionSpec =
            RealConnectionSpec.Builder(true)
                .tlsVersions(listOf(TlsVersion.TLS_1_0))
                .cipherSuites(listOf(CipherSuite.TLS_AES_256_GCM_SHA384))
                .build()

        assertThat(connectionSpec.isCompatible(tlsParameterizer)).isFalse()
    }

    @Test
    fun allEnabledCipherSuites() {
        val tlsParameterizer = mockk<ClientTlsSocket.Parameterizer>(relaxed = true)
        every { tlsParameterizer.enabledTlsVersions } returns listOf(TlsVersion.TLS_1_0)
        every { tlsParameterizer.supportedCipherSuites } returns
                listOf(
                    CipherSuite.TLS_AES_128_GCM_SHA256,
                    CipherSuite.TLS_AES_128_CCM_SHA256,
                    CipherSuite.TLS_AES_128_CCM_8_SHA256,
                    CipherSuite.TLS_AES_256_GCM_SHA384,
                )
        every { tlsParameterizer.enabledCipherSuites } returns
                listOf(
                    CipherSuite.TLS_AES_128_GCM_SHA256,
                    CipherSuite.TLS_AES_128_CCM_SHA256,
                    CipherSuite.TLS_AES_128_CCM_8_SHA256,
                )
        var connectionSpec =
            RealConnectionSpec.Builder(true)
                .tlsVersions(listOf(TlsVersion.TLS_1_0))
                .enableAllCipherSuites(true)
                .build()

        assertThat(connectionSpec.isCompatible(tlsParameterizer)).isTrue()
        connectionSpec = connectionSpec.supportedSpec(tlsParameterizer, false)

        assertThat(connectionSpec.cipherSuites).containsExactly(
            CipherSuite.TLS_AES_128_GCM_SHA256,
            CipherSuite.TLS_AES_128_CCM_SHA256, CipherSuite.TLS_AES_128_CCM_8_SHA256
        )
    }

    @Test
    fun allEnabledTlsVersions() {
        val tlsParameterizer = mockk<ClientTlsSocket.Parameterizer>(relaxed = true)
        every { tlsParameterizer.enabledTlsVersions } returns listOf(
            TlsVersion.SSL_3_0,
            TlsVersion.TLS_1_1,
            TlsVersion.TLS_1_2,
            TlsVersion.TLS_1_3,
        )
        every { tlsParameterizer.supportedCipherSuites } returns
                listOf(
                    CipherSuite.TLS_AES_128_GCM_SHA256,
                    CipherSuite.TLS_AES_128_CCM_SHA256,
                )
        every { tlsParameterizer.enabledCipherSuites } returns listOf(CipherSuite.TLS_AES_128_GCM_SHA256)
        var tlsSpec =
            RealConnectionSpec.Builder(RealConnectionSpec.COMPATIBLE_TLS)
                .enableAllTlsVersions(true)
                .build()
        assertThat(tlsSpec.tlsVersions).isNull()

        tlsSpec = tlsSpec.supportedSpec(tlsParameterizer, false)

        assertThat(tlsSpec.tlsVersions)
            .containsExactly(
                TlsVersion.SSL_3_0,
                TlsVersion.TLS_1_1,
                TlsVersion.TLS_1_2,
                TlsVersion.TLS_1_3,
            )
    }

    @Test
    fun tls_missingTlsVersion() {
        val tlsParameterizer = mockk<ClientTlsSocket.Parameterizer>(relaxed = true)
        every { tlsParameterizer.enabledTlsVersions } returns
                listOf(TlsVersion.TLS_1_1, TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
        every { tlsParameterizer.supportedCipherSuites } returns listOf(CipherSuite.TLS_AES_128_GCM_SHA256)
        every { tlsParameterizer.enabledCipherSuites } returns listOf(CipherSuite.TLS_AES_128_GCM_SHA256)
        val connectionSpec =
            RealConnectionSpec.Builder(true)
                .tlsVersions(listOf(TlsVersion.TLS_1_3))
                .cipherSuites(listOf(CipherSuite.TLS_AES_128_GCM_SHA256))
                .build()

        assertThat(connectionSpec.isCompatible(tlsParameterizer)).isTrue()
        every { tlsParameterizer.enabledTlsVersions } returns listOf(TlsVersion.TLS_1_1)
        assertThat(connectionSpec.isCompatible(tlsParameterizer)).isFalse()
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
        assertThat(set.remove(ConnectionSpec.LEGACY_TLS)).isTrue()
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
                .tlsVersions(listOf(TlsVersion.TLS_1_2))
                .cipherSuites(listOf(CipherSuite.TLS_RSA_WITH_RC4_128_MD5))
                .build()
        assertThat(connectionSpec.toString()).isEqualTo(
            "ConnectionSpec(cipherSuites=[SSL_RSA_WITH_RC4_128_MD5], tlsVersions=[TLSv1.2])",
        )
    }
}
