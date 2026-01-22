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

import jayo.Buffer
import jayo.Writer
import jayo.http.ClientRequestBody
import jayo.http.Headers
import jayo.http.MediaType
import jayo.http.MultipartBody
import jayo.utf8ByteSize
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

class MultipartBodyTest {
    @Test
    fun onePartRequired() {
        assertThatThrownBy { MultipartBody.builder().build() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Multipart body must have at least one part.")
    }

    @Test
    fun singlePart() {
        val expected =
            """
      |--123
      |
      |Hello, World!
      |--123--
      |
      """.trimMargin().replace("\n", "\r\n")
        val body =
            MultipartBody.builder()
                .boundary("123")
                .addPart(ClientRequestBody.create("Hello, World!"))
                .build()
        assertThat(body.boundary).isEqualTo("123")
        assertThat(body.type).isEqualTo(MultipartBody.MIXED)
        assertThat(body.contentType().toString())
            .isEqualTo("multipart/mixed; boundary=123")
        assertThat(body.size).isEqualTo(1)
        assertThat(body.contentByteSize()).isEqualTo(33L)
        val buffer = Buffer()
        body.writeTo(buffer)
        assertThat(body.contentByteSize()).isEqualTo(buffer.bytesAvailable())
        assertThat(buffer.readString()).isEqualTo(expected)
    }

    @Test
    fun threeParts() {
        val expected =
            """
      |--123
      |
      |Quick
      |--123
      |
      |Brown
      |--123
      |
      |Fox
      |--123--
      |
      """.trimMargin().replace("\n", "\r\n")
        val body =
            MultipartBody.builder()
                .boundary("123")
                .addPart(ClientRequestBody.create("Quick"))
                .addPart(ClientRequestBody.create("Brown"))
                .addPart(ClientRequestBody.create("Fox"))
                .build()
        assertThat(body.boundary).isEqualTo("123")
        assertThat(body.type).isEqualTo(MultipartBody.MIXED)
        assertThat(body.contentType().toString())
            .isEqualTo("multipart/mixed; boundary=123")
        assertThat(body.size).isEqualTo(3)
        assertThat(body.contentByteSize()).isEqualTo(55L)
        val buffer = Buffer()
        body.writeTo(buffer)
        assertThat(body.contentByteSize()).isEqualTo(buffer.bytesAvailable())
        assertThat(buffer.readString()).isEqualTo(expected)
    }

    @Test
    fun fieldAndTwoFiles() {
        val expected =
            """
      |--AaB03x
      |Content-Disposition: form-data; name="submit-name"
      |
      |Larry
      |--AaB03x
      |Content-Disposition: form-data; name="files"
      |Content-Type: multipart/mixed; boundary=BbC04y
      |
      |--BbC04y
      |Content-Disposition: file; filename="file1.txt"
      |Content-Type: text/plain; charset=utf-8
      |
      |... contents of file1.txt ...
      |--BbC04y
      |Content-Disposition: file; filename="file2.gif"
      |Content-Transfer-Encoding: binary
      |Content-Type: image/gif
      |
      |... contents of file2.gif ...
      |--BbC04y--
      |
      |--AaB03x--
      |
      """.trimMargin().replace("\n", "\r\n")
        val body =
            MultipartBody.builder()
                .boundary("AaB03x")
                .type(MultipartBody.FORM)
                .addFormDataPart("submit-name", "Larry")
                .addFormDataPart(
                    "files",
                    null,
                    MultipartBody
                        .builder().boundary("BbC04y")
                        .addPart(
                            Headers.of("Content-Disposition", "file; filename=\"file1.txt\""),
                            ClientRequestBody.create(
                                "... contents of file1.txt ...",
                                MediaType.get("text/plain")
                            ),
                        ).addPart(
                            Headers.of(
                                "Content-Disposition",
                                "file; filename=\"file2.gif\"",
                                "Content-Transfer-Encoding",
                                "binary",
                            ),
                            ClientRequestBody.create(
                                "... contents of file2.gif ..."
                                    .toByteArray(Charsets.UTF_8),
                                MediaType.get("image/gif")
                            ),
                        ).build(),
                ).build()
        assertThat(body.boundary).isEqualTo("AaB03x")
        assertThat(body.type).isEqualTo(MultipartBody.FORM)
        assertThat(body.contentType().toString()).isEqualTo(
            "multipart/form-data; boundary=AaB03x",
        )
        assertThat(body.size).isEqualTo(2)
        assertThat(body.contentByteSize()).isEqualTo(488L)
        val buffer = Buffer()
        body.writeTo(buffer)
        assertThat(body.contentByteSize()).isEqualTo(buffer.bytesAvailable())
        assertThat(buffer.readString()).isEqualTo(expected)
    }

    @Test
    fun stringEscapingIsWeird() {
        val expected =
            """
      |--AaB03x
      |Content-Disposition: form-data; name="field with spaces"; filename="filename with spaces.txt"
      |Content-Type: text/plain; charset=utf-8
      |
      |okay
      |--AaB03x
      |Content-Disposition: form-data; name="field with %22"
      |
      |"
      |--AaB03x
      |Content-Disposition: form-data; name="field with %22"
      |
      |%22
      |--AaB03x
      |Content-Disposition: form-data; name="field with ~"
      |
      |Alpha
      |--AaB03x--
      |
      """.trimMargin().replace("\n", "\r\n")
        val body =
            MultipartBody
                .builder().boundary("AaB03x")
                .type(MultipartBody.FORM)
                .addFormDataPart(
                    "field with spaces",
                    "filename with spaces.txt",
                    ClientRequestBody.create("okay", MediaType.get("text/plain; charset=utf-8")),
                ).addFormDataPart("field with \"", "\"")
                .addFormDataPart("field with %22", "%22")
                .addFormDataPart("field with \u007e", "Alpha")
                .build()
        val buffer = Buffer()
        body.writeTo(buffer)
        assertThat(buffer.readString()).isEqualTo(expected)
    }

    @Test
    fun streamingPartHasNoLength() {
        class StreamingBody(
            private val body: String,
        ) : ClientRequestBody {
            override fun contentType(): MediaType? = null

            override fun contentByteSize(): Long = -1L

            override fun writeTo(destination: Writer) {
                destination.write(body)
            }
        }

        val expected =
            """
      |--123
      |
      |Quick
      |--123
      |
      |Brown
      |--123
      |
      |Fox
      |--123--
      |
      """.trimMargin().replace("\n", "\r\n")
        val body =
            MultipartBody
                .builder().boundary("123")
                .addPart(ClientRequestBody.create("Quick"))
                .addPart(StreamingBody("Brown"))
                .addPart(ClientRequestBody.create("Fox"))
                .build()
        assertThat(body.boundary).isEqualTo("123")
        assertThat(body.type).isEqualTo(MultipartBody.MIXED)
        assertThat(body.contentType().toString())
            .isEqualTo("multipart/mixed; boundary=123")
        assertThat(body.size).isEqualTo(3)
        assertThat(body.contentByteSize()).isEqualTo(-1)
        val buffer = Buffer()
        body.writeTo(buffer)
        assertThat(buffer.readString()).isEqualTo(expected)
    }

    @Test
    fun contentTypeHeaderIsForbidden() {
        val multipart = MultipartBody.builder()
        assertFailsWith<IllegalArgumentException> {
            multipart.addPart(
                Headers.of("Content-Type", "text/plain"),
                ClientRequestBody.create("Hello, World!"),
            )
        }
    }

    @Test
    fun contentLengthHeaderIsForbidden() {
        val multipart = MultipartBody.builder()
        assertFailsWith<IllegalArgumentException> {
            multipart.addPart(
                Headers.of("Content-Length", "13"),
                ClientRequestBody.create("Hello, World!"),
            )
        }
    }

    @Test
    fun partAccessors() {
        val body =
            MultipartBody.builder()
                .addPart(Headers.of("Foo", "Bar"), ClientRequestBody.create("Baz"))
                .build()
        assertThat(body.size).isEqualTo(1)
        val part1Buffer = Buffer()
        val part1 = body[0]
        part1.body.writeTo(part1Buffer)
        assertThat(part1.headers).isEqualTo(Headers.of("Foo", "Bar"))
        assertThat(part1Buffer.readString()).isEqualTo("Baz")
    }

    @Test
    fun nonAsciiFilename() {
        val expected =
            """
      |--AaB03x
      |Content-Disposition: form-data; name="attachment"; filename="resumé.pdf"
      |Content-Type: application/pdf; charset=utf-8
      |
      |Jesse’s Resumé
      |--AaB03x--
      |
      """.trimMargin().replace("\n", "\r\n")
        val body =
            MultipartBody
                .builder().boundary("AaB03x")
                .type(MultipartBody.FORM)
                .addFormDataPart(
                    "attachment",
                    "resumé.pdf",
                    ClientRequestBody.create("Jesse’s Resumé", MediaType.get("application/pdf")),
                ).build()
        val buffer = Buffer()
        body.writeTo(buffer)
        assertThat(buffer.readString()).isEqualTo(expected)
    }

    @Test
    fun writeTwice() {
        val expected =
            """
      |--123
      |
      |Hello, World!
      |--123--
      |
      """.trimMargin().replace("\n", "\r\n")
        val body =
            MultipartBody
                .builder().boundary("123")
                .addPart(ClientRequestBody.create("Hello, World!"))
                .build()

        assertThat(body.isOneShot()).isEqualTo(false)

        val buffer = Buffer()
        body.writeTo(buffer)
        assertThat(body.contentByteSize()).isEqualTo(buffer.bytesAvailable())
        assertThat(buffer.readString()).isEqualTo(expected)

        val buffer2 = Buffer()
        body.writeTo(buffer2)
        assertThat(body.contentByteSize()).isEqualTo(buffer2.bytesAvailable())
        assertThat(buffer2.readString()).isEqualTo(expected)
    }

    @Test
    fun writeTwiceWithOneShot() {
        val expected =
            """
      |--123
      |
      |Hello, World!
      |--123--
      |
      """.trimMargin().replace("\n", "\r\n")
        val body =
            MultipartBody
                .builder().boundary("123")
                .addPart("Hello, World!".toOneShotRequestBody())
                .build()

        assertThat(body.isOneShot()).isEqualTo(true)

        val buffer = Buffer()
        body.writeTo(buffer)
        assertThat(body.contentByteSize()).isEqualTo(buffer.bytesAvailable())
        assertThat(buffer.readString()).isEqualTo(expected)
    }

    fun String.toOneShotRequestBody(): ClientRequestBody =
        object : ClientRequestBody {
            override fun contentType() = null

            override fun isOneShot(): Boolean = true

            override fun contentByteSize(): Long = this@toOneShotRequestBody.utf8ByteSize()

            override fun writeTo(destination: Writer) {
                destination.write(this@toOneShotRequestBody)
            }
        }
}
