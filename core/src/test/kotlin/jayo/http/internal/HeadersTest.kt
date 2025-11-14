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

import jayo.http.Headers
import jayo.http.toHeaders
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFailsWith

class HeadersTest {
    @Test
    fun ofTrims() {
        val headers = Headers.of("\t User-Agent \n", " \r JayoHttp ")
        assertThat(headers.name(0)).isEqualTo("User-Agent")
        assertThat(headers.value(0)).isEqualTo("JayoHttp")
    }

    @Test
    fun ofThrowsOddNumberOfHeaders() {
        assertFailsWith<IllegalArgumentException> {
            Headers.of("User-Agent", "OkHttp", "Content-Length")
        }
    }

    @Test
    fun ofThrowsOnEmptyName() {
        assertFailsWith<IllegalArgumentException> {
            Headers.of("", "OkHttp")
        }
    }

    @Test
    fun ofAcceptsEmptyValue() {
        val headers = Headers.of("User-Agent", "")
        assertThat(headers.value(0)).isEqualTo("")
    }

    @Test
    fun ofMakesDefensiveCopy() {
        val namesAndValues =
            arrayOf(
                "User-Agent",
                "OkHttp",
            )
        val headers = Headers.of(*namesAndValues)
        namesAndValues[1] = "Chrome"
        assertThat(headers.value(0)).isEqualTo("OkHttp")
    }

    @Test
    fun ofRejectsNullChar() {
        assertFailsWith<IllegalArgumentException> {
            Headers.of("User-Agent", "Square\u0000OkHttp")
        }
    }

    @Test
    fun ofMapThrowsOnEmptyName() {
        assertFailsWith<IllegalArgumentException> {
            mapOf("" to "OkHttp").toHeaders()
        }
    }

    @Test
    fun ofMapThrowsOnBlankName() {
        assertFailsWith<IllegalArgumentException> {
            mapOf(" " to "OkHttp").toHeaders()
        }
    }

    @Test
    fun ofMapAcceptsEmptyValue() {
        val headers = mapOf("User-Agent" to "").toHeaders()
        assertThat(headers.value(0)).isEqualTo("")
    }

    @Test
    fun ofMapTrimsKey() {
        val headers = mapOf(" User-Agent " to "OkHttp").toHeaders()
        assertThat(headers.name(0)).isEqualTo("User-Agent")
    }

    @Test
    fun ofMapTrimsValue() {
        val headers = mapOf("User-Agent" to " OkHttp ").toHeaders()
        assertThat(headers.value(0)).isEqualTo("OkHttp")
    }

    @Test
    fun ofMapMakesDefensiveCopy() {
        val namesAndValues = mutableMapOf<String, String>()
        namesAndValues["User-Agent"] = "OkHttp"
        val headers = namesAndValues.toHeaders()
        namesAndValues["User-Agent"] = "Chrome"
        assertThat(headers.value(0)).isEqualTo("OkHttp")
    }

    @Test
    fun ofMapRejectsNullCharInName() {
        assertFailsWith<IllegalArgumentException> {
            mapOf("User-\u0000Agent" to "OkHttp").toHeaders()
        }
    }

    @Test
    fun ofMapRejectsNullCharInValue() {
        assertFailsWith<IllegalArgumentException> {
            mapOf("User-Agent" to "Square\u0000OkHttp").toHeaders()
        }
    }

    @Test
    fun builderRejectsUnicodeInHeaderName() {
        assertFailsWith<IllegalArgumentException> {
            Headers.builder().add("héader1", "value1")
        }.also { expected ->
            assertThat(expected.message)
                .isEqualTo("Unexpected char 0xe9 at 1 in header name: héader1")
        }
    }

    @Test
    fun builderRejectsUnicodeInHeaderValue() {
        assertFailsWith<IllegalArgumentException> {
            Headers.builder().add("header1", "valué1")
        }.also { expected ->
            assertThat(expected.message)
                .isEqualTo("Unexpected char 0xe9 at 4 in header header1 value: valué1")
        }
    }

    @Test
    fun varargFactoryRejectsUnicodeInHeaderName() {
        assertFailsWith<IllegalArgumentException> {
            Headers.of("héader1", "value1")
        }.also { expected ->
            assertThat(expected.message)
                .isEqualTo("Unexpected char 0xe9 at 1 in header name: héader1")
        }
    }

    @Test
    fun varargFactoryRejectsUnicodeInHeaderValue() {
        assertFailsWith<IllegalArgumentException> {
            Headers.of("header1", "valué1")
        }.also { expected ->
            assertThat(expected.message)
                .isEqualTo("Unexpected char 0xe9 at 4 in header header1 value: valué1")
        }
    }

    @Test
    fun mapFactoryRejectsUnicodeInHeaderName() {
        assertFailsWith<IllegalArgumentException> {
            mapOf("héader1" to "value1").toHeaders()
        }.also { expected ->
            assertThat(expected.message)
                .isEqualTo("Unexpected char 0xe9 at 1 in header name: héader1")
        }
    }

    @Test
    fun mapFactoryRejectsUnicodeInHeaderValue() {
        assertFailsWith<IllegalArgumentException> {
            mapOf("header1" to "valué1").toHeaders()
        }.also { expected ->
            assertThat(expected.message)
                .isEqualTo("Unexpected char 0xe9 at 4 in header header1 value: valué1")
        }
    }

    @Test
    fun sensitiveHeadersNotIncludedInExceptions() {
        assertFailsWith<IllegalArgumentException> {
            Headers.builder().add("Authorization", "valué1")
        }.also { expected ->
            assertThat(expected.message)
                .isEqualTo("Unexpected char 0xe9 at 4 in header Authorization value")
        }
        assertFailsWith<IllegalArgumentException> {
            Headers.builder().add("Cookie", "valué1")
        }.also { expected ->
            assertThat(expected.message)
                .isEqualTo("Unexpected char 0xe9 at 4 in header Cookie value")
        }
        assertFailsWith<IllegalArgumentException> {
            Headers.builder().add("Proxy-Authorization", "valué1")
        }.also { expected ->
            assertThat(expected.message)
                .isEqualTo("Unexpected char 0xe9 at 4 in header Proxy-Authorization value")
        }
        assertFailsWith<IllegalArgumentException> {
            Headers.builder().add("Set-Cookie", "valué1")
        }.also { expected ->
            assertThat(expected.message)
                .isEqualTo("Unexpected char 0xe9 at 4 in header Set-Cookie value")
        }
    }

    @Test
    fun headersSize() {
        val headers =
            Headers.builder()
                .add("A", "a")
                .add("B", "bb")
                .build()
        assertThat(headers.size).isEqualTo(2)
    }

    @Test
    fun headersEquals() {
        val headers1 =
            Headers.builder()
                .add("Connection", "close")
                .add("Transfer-Encoding", "chunked")
                .build()
        val headers2 =
            Headers.builder()
                .add("Connection", "close")
                .add("Transfer-Encoding", "chunked")
                .build()
        assertThat(headers2).isEqualTo(headers1)
        assertThat(headers2.hashCode()).isEqualTo(headers1.hashCode())
    }

    @Test
    fun headersNotEquals() {
        val headers1 =
            Headers.builder()
                .add("Connection", "close")
                .add("Transfer-Encoding", "chunked")
                .build()
        val headers2 =
            Headers.builder()
                .add("Connection", "keep-alive")
                .add("Transfer-Encoding", "chunked")
                .build()
        assertThat(headers2).isNotEqualTo(headers1)
        assertThat(headers2.hashCode()).isNotEqualTo(headers1.hashCode().toLong())
    }

    @Test
    fun headersToString() {
        val headers =
            Headers.builder()
                .add("A", "a")
                .add("B", "bb")
                .build()
        assertThat(headers.toString()).isEqualTo("A: a\nB: bb\n")
    }

    @Test
    fun headersToStringRedactsSensitiveHeaders() {
        val headers =
            Headers.builder()
                .add("content-length", "99")
                .add("authorization", "peanutbutter")
                .add("proxy-authorization", "chocolate")
                .add("cookie", "drink=coffee")
                .add("set-cookie", "accessory=sugar")
                .add("user-agent", "OkHttp")
                .build()
        assertThat(headers.toString()).isEqualTo(
            """
      |content-length: 99
      |authorization: ██
      |proxy-authorization: ██
      |cookie: ██
      |set-cookie: ██
      |user-agent: OkHttp
      |
      """.trimMargin(),
        )
    }

    @Test
    fun headersAddAll() {
        val sourceHeaders =
            Headers.builder()
                .add("A", "aa")
                .add("a", "aa")
                .add("B", "bb")
                .build()
        val headers =
            Headers.builder()
                .add("A", "a")
                .addAll(sourceHeaders)
                .add("C", "c")
                .build()
        assertThat(headers.toString()).isEqualTo("A: a\nA: aa\na: aa\nB: bb\nC: c\n")
    }

    @Test
    fun nameIndexesAreStrict() {
        val headers = Headers.of("a", "b", "c", "d")
        assertFailsWith<IndexOutOfBoundsException> {
            headers.name(-1)
        }
        assertThat(headers.name(0)).isEqualTo("a")
        assertThat(headers.name(1)).isEqualTo("c")
        assertFailsWith<IndexOutOfBoundsException> {
            headers.name(2)
        }
    }

    @Test
    fun valueIndexesAreStrict() {
        val headers = Headers.of("a", "b", "c", "d")
        assertFailsWith<IndexOutOfBoundsException> {
            headers.value(-1)
        }
        assertThat(headers.value(0)).isEqualTo("b")
        assertThat(headers.value(1)).isEqualTo("d")
        assertFailsWith<IndexOutOfBoundsException> {
            headers.value(2)
        }
    }

    @Test
    fun byteCount() {
        assertThat(Headers.EMPTY.byteCount()).isEqualTo(0L)
        assertThat(
            Headers.builder()
                .add("abc", "def")
                .build()
                .byteCount(),
        ).isEqualTo(10L)
        assertThat(
            Headers.builder()
                .add("abc", "def")
                .add("ghi", "jkl")
                .build()
                .byteCount(),
        ).isEqualTo(20L)
    }

    @Test
    fun addInstant() {
        val expected = Instant.ofEpochMilli(0L)
        val headers =
            Headers.builder()
                .add("Test-Instant", expected)
                .build()
        assertThat(headers["Test-Instant"]).isEqualTo("Thu, 01 Jan 1970 00:00:00 GMT")
        assertThat(headers.getInstant("Test-Instant")).isEqualTo(expected)
    }

    @Test
    fun setInstant() {
        val expected = Instant.ofEpochMilli(1000L)
        val headers =
            Headers.builder()
                .add("Test-Instant", Instant.ofEpochMilli(0L))
                .set("Test-Instant", expected)
                .build()
        assertThat(headers["Test-Instant"]).isEqualTo("Thu, 01 Jan 1970 00:00:01 GMT")
        assertThat(headers.getInstant("Test-Instant")).isEqualTo(expected)
    }

    @Test
    fun addParsing() {
        val headers =
            Headers.builder()
                .add("foo: bar")
                .add(" foo: baz") // Name leading whitespace is trimmed.
                .add("foo : bak") // Name trailing whitespace is trimmed.
                .add("\tkey\t:\tvalue\t") // '\t' also counts as whitespace
                .add("ping:  pong  ") // Value whitespace is trimmed.
                .add("kit:kat") // Space after colon is not required.
                .build()
        assertThat(headers.values("foo")).containsExactly("bar", "baz", "bak")
        assertThat(headers.values("key")).containsExactly("value")
        assertThat(headers.values("ping")).containsExactly("pong")
        assertThat(headers.values("kit")).containsExactly("kat")
    }

    @Test
    fun addThrowsOnEmptyName() {
        assertFailsWith<IllegalArgumentException> {
            Headers.builder().add(": bar")
        }
        assertFailsWith<IllegalArgumentException> {
            Headers.builder().add(" : bar")
        }
    }

    @Test
    fun addThrowsOnNoColon() {
        assertFailsWith<IllegalArgumentException> {
            Headers.builder().add("foo bar")
        }
    }

    @Test
    fun addThrowsOnMultiColon() {
        assertFailsWith<IllegalArgumentException> {
            Headers.builder().add(":status: 200 OK")
        }
    }

    @Test
    fun addUnsafeNonAsciiRejectsUnicodeName() {
        assertFailsWith<IllegalArgumentException> {
            Headers.builder()
                .addUnsafeNonAscii("héader1", "value1")
                .build()
        }.also { expected ->
            assertThat(expected.message).isEqualTo("Unexpected char 0xe9 at 1 in header name: héader1")
        }
    }

    @Test
    fun addUnsafeNonAsciiAcceptsUnicodeValue() {
        val headers =
            Headers.builder()
                .addUnsafeNonAscii("header1", "valué1")
                .build()
        assertThat(headers.toString()).isEqualTo("header1: valué1\n")
    }

    // Fails on JS, ClassCastException: Illegal cast
    @Test
    fun ofMapThrowsOnNull() {
        assertFailsWith<NullPointerException> {
            @Suppress("UNCHECKED_CAST")
            (mapOf("User-Agent" to null) as Map<String, String>).toHeaders()
        }
    }

    @Test
    fun toMultimapGroupsHeaders() {
        val headers =
            Headers.of(
                "cache-control",
                "no-cache",
                "cache-control",
                "no-store",
                "user-agent",
                "OkHttp",
            )
        val headerMap = headers.toMultimap()
        assertThat(headerMap["cache-control"]!!.size).isEqualTo(2)
        assertThat(headerMap["user-agent"]!!.size).isEqualTo(1)
    }

    @Test
    fun toMultimapUsesCanonicalCase() {
        val headers =
            Headers.of(
                "cache-control",
                "no-store",
                "Cache-Control",
                "no-cache",
                "User-Agent",
                "OkHttp",
            )
        val headerMap = headers.toMultimap()
        assertThat(headerMap["cache-control"]!!.size).isEqualTo(2)
        assertThat(headerMap["user-agent"]!!.size).isEqualTo(1)
    }

    @Test
    fun toMultimapAllowsCaseInsensitiveGet() {
        val headers =
            Headers.of(
                "cache-control",
                "no-store",
                "Cache-Control",
                "no-cache",
            )
        val headerMap = headers.toMultimap()
        assertThat(headerMap["cache-control"]!!.size).isEqualTo(2)
        assertThat(headerMap["Cache-Control"]!!.size).isEqualTo(2)
    }

    @Test
    fun getOperator() {
        val headers = Headers.of("a", "b", "c", "d")
        assertThat(headers["a"]).isEqualTo("b")
        assertThat(headers["c"]).isEqualTo("d")
        assertThat(headers["e"]).isNull()
    }

    @Test
    fun iteratorOperator() {
        val headers = Headers.of("a", "b", "c", "d")

        val pairs = mutableListOf<Pair<String, String>>()
        for (header in headers) {
            pairs += header.name to header.value
        }

        assertThat(pairs).containsExactly("a" to "b", "c" to "d")
    }

    @Test
    fun builderGetOperator() {
        val builder = Headers.builder()
        builder.add("a", "b")
        builder.add("c", "d")
        assertThat(builder["a"]).isEqualTo("b")
        assertThat(builder["c"]).isEqualTo("d")
        assertThat(builder["e"]).isNull()
    }

    @Test
    fun builderSetOperator() {
        val builder = Headers.builder()
        builder["a"] = "b"
        builder["c"] = "d"
        builder["e"] = Instant.EPOCH
        assertThat(builder["a"]).isEqualTo("b")
        assertThat(builder["c"]).isEqualTo("d")
        assertThat(builder["e"]).isEqualTo("Thu, 01 Jan 1970 00:00:00 GMT")
    }
}
