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

package jayo.http.internal

import jayo.*
import jayo.http.ClientRequestBody
import jayo.http.Headers
import jayo.http.MultipartBody
import jayo.http.toMediaType
import jayo.http.toMediaTypeOrNull
import jayo.http.toRequestBody
import jayo.http.toResponseBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class MultipartReaderTest {
    @Test
    fun `parse multipart`() {
        val multipart =
            """
      |--simple boundary
      |Content-Type: text/plain; charset=utf-8
      |Content-ID: abc
      |
      |abcd
      |efgh
      |--simple boundary
      |Content-Type: text/plain; charset=utf-8
      |Content-ID: ijk
      |
      |ijkl
      |mnop
      |
      |--simple boundary--
      """.trimMargin()
                .replace("\n", "\r\n")

        val parts =
            RealMultipartReader(
                Buffer().write(multipart),
                "simple boundary"
            )
        assertThat(parts.boundary).isEqualTo("simple boundary")

        val partAbc = parts.nextPart()!!
        assertThat(partAbc.headers).isEqualTo(
            Headers.of(
                "Content-Type",
                "text/plain; charset=utf-8",
                "Content-ID",
                "abc",
            ),
        )
        assertThat(partAbc.body.readString()).isEqualTo("abcd\r\nefgh")

        val partIjk = parts.nextPart()!!
        assertThat(partIjk.headers).isEqualTo(
            Headers.of(
                "Content-Type",
                "text/plain; charset=utf-8",
                "Content-ID",
                "ijk",
            ),
        )
        assertThat(partIjk.body.readString()).isEqualTo("ijkl\r\nmnop\r\n")

        assertThat(parts.nextPart()).isNull()
    }

    @Test
    fun `parse from response body`() {
        val multipart =
            """
      |--simple boundary
      |
      |abcd
      |--simple boundary--
      """.trimMargin()
                .replace("\n", "\r\n")

        val responseBody =
            multipart.toResponseBody(
                "application/multipart; boundary=\"simple boundary\"".toMediaType(),
            )

        val parts = RealMultipartReader(responseBody)
        assertThat(parts.boundary).isEqualTo("simple boundary")

        val part = parts.nextPart()!!
        assertThat(part.body.readString()).isEqualTo("abcd")

        assertThat(parts.nextPart()).isNull()
    }

    @Test
    fun `truncated multipart`() {
        val multipart =
            """
      |--simple boundary
      |
      |abcd
      |efgh
      |
      """.trimMargin()
                .replace("\n", "\r\n")

        val parts =
            RealMultipartReader(
                Buffer().write(multipart),
                "simple boundary"
            )

        val part = parts.nextPart()!!
        assertFailsWith<JayoEOFException> {
            part.body.readString()
        }

        assertFailsWith<JayoEOFException> {
            assertThat(parts.nextPart()).isNull()
        }
    }

    @Test
    fun `malformed headers`() {
        val multipart =
            """
      |--simple boundary
      |abcd
      |
      """.trimMargin()
                .replace("\n", "\r\n")

        val parts =
            RealMultipartReader(
                Buffer().write(multipart),
                "simple boundary",
            )

        assertFailsWith<JayoEOFException> {
            parts.nextPart()
        }
    }

    @Test
    fun `lf instead of crlf boundary is not honored`() {
        val multipart =
            """
      |--simple boundary
      |
      |abcd
      |--simple boundary
      |
      |efgh
      |--simple boundary--
      """.trimMargin()
                .replace("\n", "\r\n")
                .replace(Regex("(?m)abcd\r\n"), "abcd\n")

        val parts =
            RealMultipartReader(
                Buffer().write(multipart),
                "simple boundary",
            )

        val part = parts.nextPart()!!
        assertThat(part.body.readString()).isEqualTo("abcd\n--simple boundary\r\n\r\nefgh")

        assertThat(parts.nextPart()).isNull()
    }

    @Test
    fun `partial boundary is not honored`() {
        val multipart =
            """
      |--simple boundary
      |
      |abcd
      |--simple boundar
      |
      |efgh
      |--simple boundary--
      """.trimMargin()
                .replace("\n", "\r\n")

        val parts =
            RealMultipartReader(
                Buffer().write(multipart),
                "simple boundary",
            )

        val part = parts.nextPart()!!
        assertThat(part.body.readString()).isEqualTo("abcd\r\n--simple boundar\r\n\r\nefgh")

        assertThat(parts.nextPart()).isNull()
    }

    @Test
    fun `do not need to read entire part`() {
        val multipart =
            """
      |--simple boundary
      |
      |abcd
      |efgh
      |ijkl
      |--simple boundary
      |
      |mnop
      |--simple boundary--
      """.trimMargin()
                .replace("\n", "\r\n")

        val parts =
            RealMultipartReader(
                Buffer().write(multipart),
                "simple boundary"
            )

        parts.nextPart()!!
        val partMno = parts.nextPart()!!
        assertThat(partMno.body.readString()).isEqualTo("mnop")

        assertThat(parts.nextPart()).isNull()
    }

    @Test
    fun `cannot read part after calling nextPart`() {
        val multipart =
            """
      |--simple boundary
      |
      |abcd
      |efgh
      |ijkl
      |--simple boundary
      |
      |mnop
      |--simple boundary--
      """.trimMargin()
                .replace("\n", "\r\n")

        val parts =
            RealMultipartReader(
                Buffer().write(multipart),
                "simple boundary"
            )

        val partAbc = parts.nextPart()!!
        val partMno = parts.nextPart()!!

        assertFailsWith<JayoClosedResourceException> {
            partAbc.body.request(20)
        }

        assertThat(partMno.body.readString()).isEqualTo("mnop")
        assertThat(parts.nextPart()).isNull()
    }

    @Test
    fun `cannot read part after calling close`() {
        val multipart =
            """
      |--simple boundary
      |
      |abcd
      |--simple boundary--
      """.trimMargin()
                .replace("\n", "\r\n")

        val parts =
            RealMultipartReader(
                Buffer().write(multipart),
                "simple boundary"
            )

        val part = parts.nextPart()!!
        parts.close()

        assertFailsWith<JayoClosedResourceException> {
            part.body.request(10)
        }
    }

    @Test
    fun `cannot call nextPart after calling close`() {
        val parts =
            RealMultipartReader(
                Buffer(),
                "simple boundary"
            )

        parts.close()

        assertFailsWith<JayoClosedResourceException> {
            parts.nextPart()
        }
    }

    @Test
    fun `zero parts`() {
        val multipart =
            """
      |--simple boundary--
      """.trimMargin()
                .replace("\n", "\r\n")

        val parts =
            RealMultipartReader(
                Buffer().write(multipart),
                "simple boundary"
            )

        assertFailsWith<JayoProtocolException> {
            parts.nextPart()
        }.also { expected ->
            assertThat(expected).hasMessage("expected at least 1 part")
        }
    }

    @Test
    fun `skip preamble`() {
        val multipart =
            """
      |this is the preamble! it is invisible to application code
      |
      |--simple boundary
      |
      |abcd
      |--simple boundary--
      """.trimMargin()
                .replace("\n", "\r\n")

        val parts =
            RealMultipartReader(
                Buffer().write(multipart),
                "simple boundary"
            )

        val part = parts.nextPart()!!
        assertThat(part.headers).isEqualTo(Headers.of())
        assertThat(part.body.readString()).isEqualTo("abcd")

        assertThat(parts.nextPart()).isNull()
    }

    @Test
    fun `skip epilogue`() {
        val multipart =
            """
      |--simple boundary
      |
      |abcd
      |--simple boundary--
      |this is the epilogue! it is also invisible to application code
      |
      """.trimMargin()
                .replace("\n", "\r\n")

        val parts =
            RealMultipartReader(
                Buffer().write(multipart),
                "simple boundary"
            )

        val part = parts.nextPart()!!
        assertThat(part.headers).isEqualTo(Headers.of())
        assertThat(part.body.readString()).isEqualTo("abcd")

        assertThat(parts.nextPart()).isNull()
    }

    @Test
    fun `skip whitespace after boundary`() {
        val multipart =
            """
      |--simple boundary
      |
      |abcd
      |--simple boundary--
      """.trimMargin()
                .replace(Regex("(?m)simple boundary$"), "simple boundary \t \t")
                .replace("\n", "\r\n")

        val parts =
            RealMultipartReader(
                Buffer().write(multipart),
                "simple boundary"
            )

        val part = parts.nextPart()!!
        assertThat(part.headers).isEqualTo(Headers.of())
        assertThat(part.body.readString()).isEqualTo("abcd")

        assertThat(parts.nextPart()).isNull()
    }

    @Test
    fun `skip whitespace after close delimiter`() {
        val multipart =
            """
      |--simple boundary
      |
      |abcd
      |--simple boundary--
      """.trimMargin()
                .replace(Regex("(?m)simple boundary--$"), "simple boundary-- \t \t")
                .replace("\n", "\r\n")

        val parts =
            RealMultipartReader(
                Buffer().write(multipart),
                "simple boundary"
            )

        val part = parts.nextPart()!!
        assertThat(part.headers).isEqualTo(Headers.of())
        assertThat(part.body.readString()).isEqualTo("abcd")

        assertThat(parts.nextPart()).isNull()
    }

    @Test
    fun `other characters after boundary`() {
        val multipart =
            """
      |--simple boundary hi
      """.trimMargin()
                .replace(Regex("(?m)simple boundary$"), "simple boundary ")
                .replace("\n", "\r\n")

        val parts =
            RealMultipartReader(
                Buffer().write(multipart),
                "simple boundary"
            )

        assertFailsWith<JayoProtocolException> {
            parts.nextPart()
        }.also { expected ->
            assertThat(expected).hasMessage("unexpected characters after boundary")
        }
    }

    @Test
    fun `whitespace before close delimiter`() {
        val multipart =
            """
      |--simple boundary
      |
      |abcd
      |--simple boundary  --
      """.trimMargin()
                .replace("\n", "\r\n")

        val parts =
            RealMultipartReader(
                Buffer().write(multipart),
                "simple boundary"
            )

        parts.nextPart()
        assertFailsWith<JayoProtocolException> {
            parts.nextPart()
        }.also { expected ->
            assertThat(expected).hasMessage("unexpected characters after boundary")
        }
    }

    /** The documentation advises that '-' is the simplest boundary possible. */
    @Test
    fun `dash boundary`() {
        val multipart =
            """
      |---
      |Content-ID: abc
      |
      |abcd
      |---
      |Content-ID: efg
      |
      |efgh
      |-----
      """.trimMargin()
                .replace("\n", "\r\n")

        val parts =
            RealMultipartReader(
                Buffer().write(multipart),
                "-"
            )

        val partAbc = parts.nextPart()!!
        assertThat(partAbc.headers).isEqualTo(Headers.of("Content-ID", "abc"))
        assertThat(partAbc.body.readString()).isEqualTo("abcd")

        val partEfg = parts.nextPart()!!
        assertThat(partEfg.headers).isEqualTo(Headers.of("Content-ID", "efg"))
        assertThat(partEfg.body.readString()).isEqualTo("efgh")

        assertThat(parts.nextPart()).isNull()
    }

    @Test
    fun `no more parts is idempotent`() {
        val multipart =
            """
      |--simple boundary
      |
      |abcd
      |--simple boundary--
      |
      |efgh
      |--simple boundary--
      """.trimMargin()
                .replace("\n", "\r\n")

        val parts =
            RealMultipartReader(
                Buffer().write(multipart),
                "simple boundary"
            )

        assertThat(parts.nextPart()).isNotNull()
        assertThat(parts.nextPart()).isNull()
        assertThat(parts.nextPart()).isNull()
    }

    @Test
    fun `empty source`() {
        val parts =
            RealMultipartReader(
                Buffer(),
                "simple boundary"
            )

        assertFailsWith<JayoEOFException> {
            parts.nextPart()
        }
    }

    /** Confirm that [MultipartBody] and [jayo.http.MultipartReader] can work together. */
    @Test
    fun `multipart round trip`() {
        val body =
            MultipartBody.builder()
                .boundary("boundary")
                .type(MultipartBody.PARALLEL)
                .addPart("Quick".toRequestBody("text/plain".toMediaType()))
                .addFormDataPart("color", "Brown")
                .addFormDataPart("animal", "fox.txt", "Fox".toRequestBody())
                .build()

        val bodyContent = Buffer()
        body.writeTo(bodyContent)

        val reader = RealMultipartReader(bodyContent, "boundary")

        val quickPart = reader.nextPart()!!
        assertThat(quickPart.headers).isEqualTo(
            Headers.of(
                "Content-Type",
                "text/plain; charset=utf-8",
            ),
        )
        assertThat(quickPart.body.readString()).isEqualTo("Quick")

        val brownPart = reader.nextPart()!!
        assertThat(brownPart.headers).isEqualTo(
            Headers.of(
                "Content-Disposition",
                "form-data; name=\"color\"",
            ),
        )
        assertThat(brownPart.body.readString()).isEqualTo("Brown")

        val foxPart = reader.nextPart()!!
        assertThat(foxPart.headers).isEqualTo(
            Headers.of(
                "Content-Disposition",
                "form-data; name=\"animal\"; filename=\"fox.txt\"",
            ),
        )
        assertThat(foxPart.body.readString()).isEqualTo("Fox")

        assertThat(reader.nextPart()).isNull()
    }

    /**
     * Read 100 MiB of 'a' chars. This was really slow due to a performance bug in [RealMultipartReader], and will be
     * really slow if we regress the fix for that.
     */
    @Test
    fun `reading a large part with small byteCount`() {
        val multipartBody =
            MultipartBody.builder()
                .boundary("foo")
                .addPart(
                    Headers.of("header-name", "header-value"),
                    object : ClientRequestBody {
                        override fun contentType() = "application/octet-stream".toMediaTypeOrNull()

                        override fun contentByteSize() = 1024L * 1024L * 100L

                        override fun writeTo(destination: Writer) {
                            val a1024x1024 = "a".repeat(1024 * 1024)
                            repeat(100) {
                                destination.write(a1024x1024)
                            }
                        }
                    },
                ).build()
        val buffer = Buffer()
        multipartBody.writeTo(buffer)

        val multipartReader = RealMultipartReader(buffer, "foo")
        val onlyPart = multipartReader.nextPart()!!
        assertThat(onlyPart.headers).isEqualTo(
            Headers.of(
                "header-name",
                "header-value",
                "Content-Type",
                "application/octet-stream",
            ),
        )
        val readBuff = Buffer()
        var byteCount = 0L
        while (true) {
            val readByteCount = onlyPart.body.readAtMostTo(readBuff, 1024L)
            if (readByteCount == -1L) {
                break
            }
            byteCount += readByteCount
            assertThat(readBuff.readString()).isEqualTo("a".repeat(readByteCount.toInt()))
        }
        assertThat(byteCount).isEqualTo(1024L * 1024L * 100L)
        assertThat(multipartReader.nextPart()).isNull()
    }
}
