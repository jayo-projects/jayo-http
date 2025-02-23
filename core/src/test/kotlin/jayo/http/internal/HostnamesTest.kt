/*
 * Copyright (c) 2025-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 *
 * Forked from OkHttp (https://github.com/square/okhttp), original copyright is below
 *
 * Copyright (C) 2023 Block, Inc.
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

import jayo.http.internal.HostnameUtils.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HostnamesTest {
    @Test
    fun canonicalizeInetAddressNotMapped() {
        val addressA = decodeIpv6("::1")!!
        assertThat(canonicalizeInetAddress(addressA)).isEqualTo(addressA)

        val addressB = byteArrayOf(127, 0, 0, 1)
        assertThat(canonicalizeInetAddress(addressB)).isEqualTo(addressB)

        val addressC = byteArrayOf(192.toByte(), 168.toByte(), 0, 1)
        assertThat(canonicalizeInetAddress(addressC)).isEqualTo(addressC)

        val addressD = decodeIpv6("abcd:ef01:2345:6789:abcd:ef01:2345:6789")!!
        assertThat(canonicalizeInetAddress(addressD)).isEqualTo(addressD)

        val addressE = decodeIpv6("2001:db8::1:0:0:1")!!
        assertThat(canonicalizeInetAddress(addressE)).isEqualTo(addressE)

        val addressF = decodeIpv6("0:0:0:0:0:ffff:7f00:1")!!
        assertThat(canonicalizeInetAddress(addressF)).isEqualTo(addressB)

        val addressG = decodeIpv6("0:0:0:0:0:ffff:c0a8:1")!!
        assertThat(canonicalizeInetAddress(addressG)).isEqualTo(addressC)
    }

    @Test
    fun canonicalizeInetAddressMapped() {
        val addressAIpv6 = decodeIpv6("0:0:0:0:0:ffff:7f00:1")!!
        val addressAIpv4 = byteArrayOf(127, 0, 0, 1)
        assertThat(canonicalizeInetAddress(addressAIpv6)).isEqualTo(addressAIpv4)

        val addressBIpv6 = decodeIpv6("0:0:0:0:0:ffff:c0a8:1")!!
        val addressBIpv4 = byteArrayOf(192.toByte(), 168.toByte(), 0, 1)
        assertThat(canonicalizeInetAddress(addressBIpv6)).isEqualTo(addressBIpv4)
    }

    @Test
    fun canonicalizeInetAddressIPv6RepresentationOfCompatibleIPV4() {
        val addressAIpv6 = decodeIpv6("::192.168.0.1")!!
        assertThat(canonicalizeInetAddress(addressAIpv6)).isEqualTo(
            ByteArray(12) +
                    byteArrayOf(
                        192.toByte(),
                        168.toByte(),
                        0,
                        1,
                    ),
        )
    }

    @Test
    fun canonicalizeInetAddressIPv6RepresentationOfMappedIPV4() {
        val addressAIpv6 = decodeIpv6("::FFFF:192.168.0.1")!!
        assertThat(canonicalizeInetAddress(addressAIpv6)).isEqualTo(byteArrayOf(192.toByte(), 168.toByte(), 0, 1))
    }

    @Test
    fun inet4AddressToAsciif() {
        assertThat(
            inet4AddressToAscii(byteArrayOf(0, 0, 0, 0)),
        ).isEqualTo("0.0.0.0")

        assertThat(
            inet4AddressToAscii(
                byteArrayOf(1, 2, 3, 4),
            ),
        ).isEqualTo("1.2.3.4")

        assertThat(
            inet4AddressToAscii(
                byteArrayOf(127, 0, 0, 1),
            ),
        ).isEqualTo("127.0.0.1")

        assertThat(
            inet4AddressToAscii(
                byteArrayOf(192.toByte(), 168.toByte(), 0, 1),
            ),
        ).isEqualTo("192.168.0.1")

        assertThat(
            inet4AddressToAscii(
                byteArrayOf(252.toByte(), 253.toByte(), 254.toByte(), 255.toByte()),
            ),
        ).isEqualTo("252.253.254.255")

        assertThat(
            inet4AddressToAscii(
                byteArrayOf(255.toByte(), 255.toByte(), 255.toByte(), 255.toByte()),
            ),
        ).isEqualTo("255.255.255.255")
    }

    private fun decodeIpv6(input: String): ByteArray? = decodeIpv6(input, 0, input.length)

    @Test
    fun testToCanonicalHost() {
        // IPv4
        assertThat(toCanonicalHost("127.0.0.1")).isEqualTo("127.0.0.1")
        assertThat(toCanonicalHost("1.2.3.4")).isEqualTo("1.2.3.4")

        // IPv6
        assertThat(toCanonicalHost("::1")).isEqualTo("::1")
        assertThat(toCanonicalHost("2001:db8::1")).isEqualTo("2001:db8::1")
        assertThat(toCanonicalHost("::ffff:192.168.0.1")).isEqualTo("192.168.0.1")
        assertThat(toCanonicalHost("FEDC:BA98:7654:3210:FEDC:BA98:7654:3210"))
            .isEqualTo("fedc:ba98:7654:3210:fedc:ba98:7654:3210")

        assertThat(toCanonicalHost("1080:0:0:0:8:800:200C:417A")).isEqualTo("1080::8:800:200c:417a")

        assertThat(toCanonicalHost("1080::8:800:200C:417A")).isEqualTo("1080::8:800:200c:417a")
        assertThat(toCanonicalHost("FF01::101")).isEqualTo("ff01::101")
        assertThat(toCanonicalHost("0:0:0:0:0:FFFF:129.144.52.38")).isEqualTo("129.144.52.38")

        assertThat(toCanonicalHost("::FFFF:129.144.52.38")).isEqualTo("129.144.52.38")

        // Hostnames
        assertThat(toCanonicalHost("WwW.GoOgLe.cOm")).isEqualTo("www.google.com")
        assertThat(toCanonicalHost("localhost")).isEqualTo("localhost")
        assertThat(toCanonicalHost("☃.net")).isEqualTo("xn--n3h.net")

        // IPv4 Compatible or Mapped addresses
        assertThat(toCanonicalHost("::192.168.0.1")).isEqualTo("::c0a8:1")
        assertThat(toCanonicalHost("::FFFF:192.168.0.1")).isEqualTo("192.168.0.1")
        assertThat(toCanonicalHost("0:0:0:0:0:0:13.1.68.3")).isEqualTo("::d01:4403")
        assertThat(toCanonicalHost("::13.1.68.3")).isEqualTo("::d01:4403")
    }
}