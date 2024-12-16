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

import jayo.decodeBase64
import jayo.http.CertificatePinner
import jayo.http.CertificatePinner.pin
import jayo.tls.HeldCertificate
import jayo.tls.JayoTlsPeerUnverifiedException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class CertificatePinnerTest {
    @Test
    fun malformedPin() {
        val builder = CertificatePinner.builder()
        assertFailsWith<IllegalArgumentException> {
            builder.add("example.com", "md5/DmxUShsZuNiqPQsX2Oi9uv2sCnw=")
        }
    }

    @Test
    fun malformedBase64() {
        val builder = CertificatePinner.builder()
        assertFailsWith<IllegalArgumentException> {
            builder.add("example.com", "sha1/DmxUShsZuNiqPQsX2Oi9uv2sCnw*")
        }
    }

    /** Multiple certificates generated from the same keypair have the same pin.  */
    @Test
    fun sameKeypairSamePin() {
        val heldCertificateA2 =
            HeldCertificate.builder()
                .keyPair(certA1.keyPair)
                .serialNumber(101L)
                .build()
        val keypairACertificate2Pin = pin(heldCertificateA2.certificate)
        val heldCertificateB2 =
            HeldCertificate.builder()
                .keyPair(certB1.keyPair)
                .serialNumber(201L)
                .build()
        val keypairBCertificate2Pin = pin(heldCertificateB2.certificate)
        assertThat(keypairACertificate2Pin).isEqualTo(certA1Sha256Pin)
        assertThat(keypairBCertificate2Pin).isEqualTo(certB1Sha256Pin)
        assertThat(certB1Sha256Pin).isNotEqualTo(certA1Sha256Pin)
    }

    @Test
    fun successfulCheck() {
        val certificatePinner =
            CertificatePinner.builder()
                .add("example.com", certA1Sha256Pin)
                .build()
        certificatePinner.check("example.com", listOf(certA1.certificate))
    }

    @Test
    fun successfulMatchAcceptsAnyMatchingCertificate() {
        val certificatePinner =
            CertificatePinner.builder()
                .add("example.com", certB1Sha256Pin)
                .build()
        certificatePinner.check(
            "example.com",
            listOf(certA1.certificate, certB1.certificate),
        )
    }

    @Test
    fun unsuccessfulCheck() {
        val certificatePinner =
            CertificatePinner.builder()
                .add("example.com", certA1Sha256Pin)
                .build()
        assertFailsWith<JayoTlsPeerUnverifiedException> {
            certificatePinner.check("example.com", listOf(certB1.certificate))
        }
    }

    @Test
    fun multipleCertificatesForOneHostname() {
        val certificatePinner =
            CertificatePinner.builder()
                .add("example.com", certA1Sha256Pin, certB1Sha256Pin)
                .build()
        certificatePinner.check("example.com", listOf(certA1.certificate))
        certificatePinner.check("example.com", listOf(certB1.certificate))
    }

    @Test
    fun multipleHostnamesForOneCertificate() {
        val certificatePinner =
            CertificatePinner.builder()
                .add("example.com", certA1Sha256Pin)
                .add("www.example.com", certA1Sha256Pin)
                .build()
        certificatePinner.check("example.com", listOf(certA1.certificate))
        certificatePinner.check("www.example.com", listOf(certA1.certificate))
    }

    @Test
    fun absentHostnameMatches() {
        val certificatePinner = CertificatePinner.builder().build()
        certificatePinner.check("example.com", listOf(certA1.certificate))
    }

    @Test
    fun successfulCheckForWildcardHostname() {
        val certificatePinner =
            CertificatePinner.builder()
                .add("*.example.com", certA1Sha256Pin)
                .build()
        certificatePinner.check("a.example.com", listOf(certA1.certificate))
    }

    @Test
    fun successfulMatchAcceptsAnyMatchingCertificateForWildcardHostname() {
        val certificatePinner =
            CertificatePinner.builder()
                .add("*.example.com", certB1Sha256Pin)
                .build()
        certificatePinner.check(
            "a.example.com",
            listOf(certA1.certificate, certB1.certificate),
        )
    }

    @Test
    fun unsuccessfulCheckForWildcardHostname() {
        val certificatePinner =
            CertificatePinner.builder()
                .add("*.example.com", certA1Sha256Pin)
                .build()
        assertFailsWith<JayoTlsPeerUnverifiedException> {
            certificatePinner.check("a.example.com", listOf(certB1.certificate))
        }
    }

    @Test
    fun multipleCertificatesForOneWildcardHostname() {
        val certificatePinner =
            CertificatePinner.builder()
                .add("*.example.com", certA1Sha256Pin, certB1Sha256Pin)
                .build()
        certificatePinner.check("a.example.com", listOf(certA1.certificate))
        certificatePinner.check("a.example.com", listOf(certB1.certificate))
    }

    @Test
    fun successfulCheckForOneHostnameWithWildcardAndDirectCertificate() {
        val certificatePinner =
            CertificatePinner.builder()
                .add("*.example.com", certA1Sha256Pin)
                .add("a.example.com", certB1Sha256Pin)
                .build()
        certificatePinner.check("a.example.com", listOf(certA1.certificate))
        certificatePinner.check("a.example.com", listOf(certB1.certificate))
    }

    @Test
    fun unsuccessfulCheckForOneHostnameWithWildcardAndDirectCertificate() {
        val certificatePinner =
            CertificatePinner.builder()
                .add("*.example.com", certA1Sha256Pin)
                .add("a.example.com", certB1Sha256Pin)
                .build()
        assertFailsWith<JayoTlsPeerUnverifiedException> {
            certificatePinner.check("a.example.com", listOf(certC1.certificate))
        }
    }

    @Test
    fun checkForHostnameWithDoubleAsterisk() {
        val certificatePinner =
            CertificatePinner.builder()
                .add("**.example.co.uk", certA1Sha256Pin)
                .build()

        // Should be pinned:
        assertFailsWith<JayoTlsPeerUnverifiedException> {
            certificatePinner.check("example.co.uk", listOf(certB1.certificate))
        }
        assertFailsWith<JayoTlsPeerUnverifiedException> {
            certificatePinner.check("foo.example.co.uk", listOf(certB1.certificate))
        }
        assertFailsWith<JayoTlsPeerUnverifiedException> {
            certificatePinner.check("foo.bar.example.co.uk", listOf(certB1.certificate))
        }
        assertFailsWith<JayoTlsPeerUnverifiedException> {
            certificatePinner.check("foo.bar.baz.example.co.uk", listOf(certB1.certificate))
        }

        // Should not be pinned:
        certificatePinner.check("uk", listOf(certB1.certificate))
        certificatePinner.check("co.uk", listOf(certB1.certificate))
        certificatePinner.check("anotherexample.co.uk", listOf(certB1.certificate))
        certificatePinner.check("foo.anotherexample.co.uk", listOf(certB1.certificate))
    }

    @Test
    fun testBadPin() {
        assertFailsWith<IllegalArgumentException> {
            CertificatePinner.Pin.create(
                "example.co.uk",
                "sha256/a",
            )
        }
    }

    @Test
    fun testBadAlgorithm() {
        assertFailsWith<IllegalArgumentException> {
            CertificatePinner.Pin.create(
                "example.co.uk",
                "sha512/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            )
        }
    }

    @Test
    fun testBadHost() {
        assertFailsWith<IllegalArgumentException> {
            CertificatePinner.Pin.create(
                "example.*",
                "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            )
        }
    }

    @Test
    fun successfulFindMatchingPins() {
        val certificatePinner =
            CertificatePinner.builder()
                .add("first.com", certA1Sha256Pin, certB1Sha256Pin)
                .add("second.com", certC1Sha256Pin)
                .build()

        val expectedPins =
            listOf(
                CertificatePinner.Pin.create("first.com", certA1Sha256Pin),
                CertificatePinner.Pin.create("first.com", certB1Sha256Pin),
            )
        assertThat(certificatePinner.findMatchingPins("first.com"))
            .containsExactlyInAnyOrderElementsOf(expectedPins)
    }

    @Test
    fun successfulFindMatchingPinsForWildcardAndDirectCertificates() {
        val certificatePinner =
            CertificatePinner.builder()
                .add("*.example.com", certA1Sha256Pin)
                .add("a.example.com", certB1Sha256Pin)
                .add("b.example.com", certC1Sha256Pin)
                .build()

        val expectedPins =
            listOf(
                CertificatePinner.Pin.create("*.example.com", certA1Sha256Pin),
                CertificatePinner.Pin.create("a.example.com", certB1Sha256Pin),
            )
        assertThat(certificatePinner.findMatchingPins("a.example.com"))
            .containsExactlyInAnyOrderElementsOf(expectedPins)
    }

    @Test
    fun wildcardHostnameShouldNotMatchThroughDot() {
        val certificatePinner =
            CertificatePinner.builder()
                .add("*.example.com", certA1Sha256Pin)
                .build()

        assertThat(certificatePinner.findMatchingPins("example.com")).isEmpty()
        assertThat(certificatePinner.findMatchingPins("..example.com")).isEmpty()
        assertThat(certificatePinner.findMatchingPins("a..example.com")).isEmpty()
        assertThat(certificatePinner.findMatchingPins("a.b.example.com")).isEmpty()
    }

    @Test
    fun doubleWildcardHostnameShouldMatchThroughDot() {
        val certificatePinner =
            CertificatePinner.builder()
                .add("**.example.com", certA1Sha256Pin)
                .build()

        val expectedPin1 = listOf(CertificatePinner.Pin.create("**.example.com", certA1Sha256Pin))
        assertThat(certificatePinner.findMatchingPins("example.com")).isEqualTo(expectedPin1)
        assertThat(certificatePinner.findMatchingPins(".example.com")).isEqualTo(expectedPin1)
        assertThat(certificatePinner.findMatchingPins("..example.com")).isEqualTo(expectedPin1)
        assertThat(certificatePinner.findMatchingPins("a..example.com")).isEqualTo(expectedPin1)
        assertThat(certificatePinner.findMatchingPins("a.b.example.com")).isEqualTo(expectedPin1)
    }

    @Test
    fun doubleWildcardHostnameShouldNotMatchSuffix() {
        val certificatePinner =
            CertificatePinner.builder()
                .add("**.example.com", certA1Sha256Pin)
                .build()

        assertThat(certificatePinner.findMatchingPins("xample.com")).isEmpty()
        assertThat(certificatePinner.findMatchingPins("dexample.com")).isEmpty()
        assertThat(certificatePinner.findMatchingPins("barnexample.com")).isEmpty()
    }

    @Test
    fun successfulFindMatchingPinsIgnoresCase() {
        val certificatePinner =
            CertificatePinner.builder()
                .add("EXAMPLE.com", certA1Sha256Pin)
                .add("*.MyExample.Com", certB1Sha256Pin)
                .build()

        val expectedPin1 = listOf(CertificatePinner.Pin.create("EXAMPLE.com", certA1Sha256Pin))
        assertThat(certificatePinner.findMatchingPins("example.com")).isEqualTo(expectedPin1)

        val expectedPin2 = listOf(CertificatePinner.Pin.create("*.MyExample.Com", certB1Sha256Pin))
        assertThat(certificatePinner.findMatchingPins("a.myexample.com")).isEqualTo(expectedPin2)
    }

    @Test
    fun successfulFindMatchingPinPunycode() {
        val certificatePinner =
            CertificatePinner.builder()
                .add("σkhttp.com", certA1Sha256Pin)
                .build()

        val expectedPin = listOf(CertificatePinner.Pin.create("σkhttp.com", certA1Sha256Pin))
        assertThat(certificatePinner.findMatchingPins("xn--khttp-fde.com")).isEqualTo(expectedPin)
    }

    @Test
    fun checkSubstringMatch() {
        val certificatePinner =
            CertificatePinner.builder()
                .add("*.example.com", certA1Sha256Pin)
                .build()

        assertThat(certificatePinner.findMatchingPins("a.example.com.notexample.com")).isEmpty()
        assertThat(certificatePinner.findMatchingPins("example.com.notexample.com")).isEmpty()
        assertThat(certificatePinner.findMatchingPins("notexample.com")).isEmpty()
        assertThat(certificatePinner.findMatchingPins("example.com")).isEmpty()
        assertThat(certificatePinner.findMatchingPins("a.b.example.com")).isEmpty()
        assertThat(certificatePinner.findMatchingPins("ple.com")).isEmpty()
        assertThat(certificatePinner.findMatchingPins("com")).isEmpty()

        val expectedPin = CertificatePinner.Pin.create("*.example.com", certA1Sha256Pin)
        assertThat(certificatePinner.findMatchingPins("a.example.com")).containsExactly(expectedPin)
        assertThat(certificatePinner.findMatchingPins(".example.com")).containsExactly(expectedPin)
        assertThat(certificatePinner.findMatchingPins("example.example.com"))
            .containsExactly(expectedPin)
    }

    @Test
    fun testGoodPin() {
        val pin =
            CertificatePinner.Pin.create(
                "**.example.co.uk",
                "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            )
        assertEquals(
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=".decodeBase64(),
            pin.hash,
        )
        assertEquals("sha256", pin.hashAlgorithm)
        assertEquals("**.example.co.uk", pin.pattern)
        Assertions.assertTrue(pin.matchesHostname("www.example.co.uk"))
        Assertions.assertTrue(pin.matchesHostname("gopher.example.co.uk"))
        Assertions.assertFalse(pin.matchesHostname("www.example.com"))
    }

    @Test
    fun testMatchesSha256() {
        val pin = CertificatePinner.Pin.create("example.com", certA1Sha256Pin)
        Assertions.assertTrue(pin.matchesCertificate(certA1.certificate))
        Assertions.assertFalse(pin.matchesCertificate(certB1.certificate))
    }

    @Test
    fun pinList() {
        val builder =
            CertificatePinner.builder()
                .add("example.com", certA1Sha256Pin)
                .add("www.example.com", certA1Sha256Pin)
        val certificatePinner = builder.build()
        val expectedPins =
            setOf(
                CertificatePinner.Pin.create("example.com", certA1Sha256Pin),
                CertificatePinner.Pin.create("www.example.com", certA1Sha256Pin),
            )
        assertThat(certificatePinner.pins).isEqualTo(expectedPins)
    }

    companion object {
        val certA1: HeldCertificate =
            HeldCertificate.builder()
                .serialNumber(100L)
                .build()
        val certA1Sha256Pin = pin(certA1.certificate)
        val certB1: HeldCertificate =
            HeldCertificate.builder()
                .serialNumber(200L)
                .build()
        val certB1Sha256Pin = pin(certB1.certificate)
        val certC1: HeldCertificate =
            HeldCertificate.builder()
                .serialNumber(300L)
                .build()
        val certC1Sha256Pin = pin(certC1.certificate)
    }
}
