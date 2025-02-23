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

import jayo.tls.ClientHandshakeCertificates
import jayo.tls.HeldCertificate
import jayo.tls.JayoTlsPeerUnverifiedException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.cert.Certificate
import kotlin.test.assertFailsWith

class CertificateChainCleanerTest {
    @Test
    fun equalsFromCertificate() {
        val rootA =
            HeldCertificate.builder()
                .serialNumber(1L)
                .build()
        val rootB =
            HeldCertificate.builder()
                .serialNumber(2L)
                .build()
        assertThat(CertificateChainCleaner(rootB.certificate, rootA.certificate))
            .isEqualTo(
                CertificateChainCleaner(
                    rootA.certificate,
                    rootB.certificate
                )
            )
    }

    @Test
    fun equalsFromTrustManager() {
        val handshakeCertificates = ClientHandshakeCertificates.builder().build()
        val x509TrustManager = handshakeCertificates.trustManager
        assertThat(CertificateChainCleaner(x509TrustManager)).isEqualTo(
            CertificateChainCleaner(x509TrustManager)
        )
    }

    @Test
    fun normalizeSingleSelfSignedCertificate() {
        val root =
            HeldCertificate.builder()
                .serialNumber(1L)
                .build()
        val cleaner = CertificateChainCleaner(root.certificate)
        assertThat(cleaner.clean(list(root))).isEqualTo(list(root))
    }

    @Test
    fun normalizeUnknownSelfSignedCertificate() {
        val root =
            HeldCertificate.builder()
                .serialNumber(1L)
                .build()
        val cleaner = CertificateChainCleaner()
        assertFailsWith<JayoTlsPeerUnverifiedException> {
            cleaner.clean(list(root))
        }
    }

    @Test
    fun orderedChainOfCertificatesWithRoot() {
        val root =
            HeldCertificate.builder()
                .serialNumber(1L)
                .certificateAuthority(1)
                .build()
        val certA =
            HeldCertificate.builder()
                .serialNumber(2L)
                .certificateAuthority(0)
                .signedBy(root)
                .build()
        val certB =
            HeldCertificate.builder()
                .serialNumber(3L)
                .signedBy(certA)
                .build()
        val cleaner = CertificateChainCleaner(root.certificate)
        assertThat(cleaner.clean(list(certB, certA, root)))
            .isEqualTo(list(certB, certA, root))
    }

    @Test
    fun orderedChainOfCertificatesWithoutRoot() {
        val root =
            HeldCertificate.builder()
                .serialNumber(1L)
                .certificateAuthority(1)
                .build()
        val certA =
            HeldCertificate.builder()
                .serialNumber(2L)
                .certificateAuthority(0)
                .signedBy(root)
                .build()
        val certB =
            HeldCertificate.builder()
                .serialNumber(3L)
                .signedBy(certA)
                .build()
        val cleaner = CertificateChainCleaner(root.certificate)
        // Root is added!
        assertThat(cleaner.clean(list(certB, certA))).isEqualTo(
            list(certB, certA, root),
        )
    }

    @Test
    fun unorderedChainOfCertificatesWithRoot() {
        val root =
            HeldCertificate.builder()
                .serialNumber(1L)
                .certificateAuthority(2)
                .build()
        val certA =
            HeldCertificate.builder()
                .serialNumber(2L)
                .certificateAuthority(1)
                .signedBy(root)
                .build()
        val certB =
            HeldCertificate.builder()
                .serialNumber(3L)
                .certificateAuthority(0)
                .signedBy(certA)
                .build()
        val certC =
            HeldCertificate.builder()
                .serialNumber(4L)
                .signedBy(certB)
                .build()
        val cleaner = CertificateChainCleaner(root.certificate)
        assertThat(cleaner.clean(list(certC, certA, root, certB))).isEqualTo(
            list(certC, certB, certA, root),
        )
    }

    @Test
    fun unorderedChainOfCertificatesWithoutRoot() {
        val root =
            HeldCertificate.builder()
                .serialNumber(1L)
                .certificateAuthority(2)
                .build()
        val certA =
            HeldCertificate.builder()
                .serialNumber(2L)
                .certificateAuthority(1)
                .signedBy(root)
                .build()
        val certB =
            HeldCertificate.builder()
                .serialNumber(3L)
                .certificateAuthority(0)
                .signedBy(certA)
                .build()
        val certC =
            HeldCertificate.builder()
                .serialNumber(4L)
                .signedBy(certB)
                .build()
        val cleaner = CertificateChainCleaner(root.certificate)
        assertThat(cleaner.clean(list(certC, certA, certB))).isEqualTo(
            list(certC, certB, certA, root),
        )
    }

    @Test
    fun unrelatedCertificatesAreOmitted() {
        val root =
            HeldCertificate.builder()
                .serialNumber(1L)
                .certificateAuthority(1)
                .build()
        val certA =
            HeldCertificate.builder()
                .serialNumber(2L)
                .certificateAuthority(0)
                .signedBy(root)
                .build()
        val certB =
            HeldCertificate.builder()
                .serialNumber(3L)
                .signedBy(certA)
                .build()
        val certUnnecessary =
            HeldCertificate.builder()
                .serialNumber(4L)
                .build()
        val cleaner = CertificateChainCleaner(root.certificate)
        assertThat(cleaner.clean(list(certB, certUnnecessary, certA, root)))
            .isEqualTo(
                list(certB, certA, root),
            )
    }

    @Test
    fun chainGoesAllTheWayToSelfSignedRoot() {
        val selfSigned =
            HeldCertificate.builder()
                .serialNumber(1L)
                .certificateAuthority(2)
                .build()
        val trusted =
            HeldCertificate.builder()
                .serialNumber(2L)
                .signedBy(selfSigned)
                .certificateAuthority(1)
                .build()
        val certA =
            HeldCertificate.builder()
                .serialNumber(3L)
                .certificateAuthority(0)
                .signedBy(trusted)
                .build()
        val certB =
            HeldCertificate.builder()
                .serialNumber(4L)
                .signedBy(certA)
                .build()
        val cleaner =
            CertificateChainCleaner(
                selfSigned.certificate,
                trusted.certificate,
            )
        assertThat(cleaner.clean(list(certB, certA))).isEqualTo(
            list(certB, certA, trusted, selfSigned),
        )
        assertThat(cleaner.clean(list(certB, certA, trusted))).isEqualTo(
            list(certB, certA, trusted, selfSigned),
        )
        assertThat(cleaner.clean(list(certB, certA, trusted, selfSigned)))
            .isEqualTo(
                list(certB, certA, trusted, selfSigned),
            )
    }

    @Test
    fun trustedRootNotSelfSigned() {
        val unknownSigner =
            HeldCertificate.builder()
                .serialNumber(1L)
                .certificateAuthority(2)
                .build()
        val trusted =
            HeldCertificate.builder()
                .signedBy(unknownSigner)
                .certificateAuthority(1)
                .serialNumber(2L)
                .build()
        val intermediateCa =
            HeldCertificate.builder()
                .signedBy(trusted)
                .certificateAuthority(0)
                .serialNumber(3L)
                .build()
        val certificate =
            HeldCertificate.builder()
                .signedBy(intermediateCa)
                .serialNumber(4L)
                .build()
        val cleaner = CertificateChainCleaner(trusted.certificate)
        assertThat(cleaner.clean(list(certificate, intermediateCa)))
            .isEqualTo(
                list(certificate, intermediateCa, trusted),
            )
        assertThat(cleaner.clean(list(certificate, intermediateCa, trusted)))
            .isEqualTo(
                list(certificate, intermediateCa, trusted),
            )
    }

    @Test
    fun chainMaxLength() {
        val heldCertificates = chainOfLength(10)
        val certificates: MutableList<Certificate> = ArrayList()
        for (heldCertificate in heldCertificates) {
            certificates.add(heldCertificate.certificate)
        }
        val root = heldCertificates[heldCertificates.size - 1].certificate
        val cleaner = CertificateChainCleaner(root)
        assertThat(cleaner.clean(certificates)).isEqualTo(certificates)
        assertThat(cleaner.clean(certificates.subList(0, 9))).isEqualTo(
            certificates,
        )
    }

    @Test
    fun chainTooLong() {
        val heldCertificates = chainOfLength(11)
        val certificates: MutableList<Certificate> = ArrayList()
        for (heldCertificate in heldCertificates) {
            certificates.add(heldCertificate.certificate)
        }
        val root = heldCertificates[heldCertificates.size - 1].certificate
        val cleaner = CertificateChainCleaner(root)
        assertFailsWith<JayoTlsPeerUnverifiedException> {
            cleaner.clean(certificates)
        }
    }

    /** @return a chain starting at the leaf certificate and progressing to the root.  */
    private fun chainOfLength(length: Int): List<HeldCertificate> {
        val result = mutableListOf<HeldCertificate>()
        for (i in 1..length) {
            result.add(
                0,
                HeldCertificate.builder()
                    .signedBy(if (result.isNotEmpty()) result[0] else null)
                    .certificateAuthority(length - i)
                    .serialNumber(i.toLong())
                    .build(),
            )
        }
        return result
    }

    private fun list(vararg heldCertificates: HeldCertificate): List<Certificate> {
        val result: MutableList<Certificate> = ArrayList()
        for (heldCertificate in heldCertificates) {
            result.add(heldCertificate.certificate)
        }
        return result
    }
}
