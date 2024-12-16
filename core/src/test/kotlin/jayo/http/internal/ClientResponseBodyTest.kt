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

import jayo.*
import jayo.http.ClientResponseBody
import jayo.http.MediaType
import jayo.http.toMediaType
import jayo.http.toResponseBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.charset.UnsupportedCharsetException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFailsWith

class ClientResponseBodyTest {
    @Test
    fun sourceEmpty() {
        val mediaType = "any/thing; charset=${null}".toMediaType()
        val body = "".decodeHex().toResponseBody(mediaType)
        val reader = body.reader()
        assertThat(reader.exhausted()).isTrue()
        assertThat(reader.readString()).isEqualTo("")
    }

    @Test
    fun sourceClosesUnderlyingSource() {
        var closed = false

        val body: ClientResponseBody =
            object : ClientResponseBody() {
                override fun contentType(): MediaType? {
                    return null
                }

                override fun contentByteSize(): Long {
                    return 5
                }

                override fun reader(): Reader {
                    val source = Buffer().write("hello")
                    return object : ForwardingRawReader(source) {
                        override fun close() {
                            closed = true
                            super.close()
                        }
                    }.buffered()
                }
            }
        body.reader().close()
        assertThat(closed).isTrue()
    }

    @Test
    fun throwingUnderlyingSourceClosesQuietly() {
        val body: ClientResponseBody =
            object : ClientResponseBody() {
                override fun contentType(): MediaType? {
                    return null
                }

                override fun contentByteSize(): Long {
                    return 5
                }

                override fun reader(): Reader {
                    val source = Buffer().write("hello")
                    return object : ForwardingRawReader(source) {
                        override fun close() {
                            throw JayoException("Broken!")
                        }
                    }.buffered()
                }
            }
        assertThat(body.reader().readString()).isEqualTo("hello")
        body.close()
    }

    @Test
    fun unicodeText() {
        val text = "eile oli oliiviõli"
        val body = ClientResponseBody.create(text)
        assertThat(body.string()).isEqualTo(text)
        val body2 = text.toResponseBody()
        assertThat(body2.string()).isEqualTo(text)
    }

    @Test
    fun unicodeTextWithCharset() {
        val text = "eile oli oliiviõli"
        val body = ClientResponseBody.create(text, "text/plain; charset=UTF-8".toMediaType())
        assertThat(body.string()).isEqualTo(text)
        val body2 = text.toResponseBody("text/plain; charset=UTF-8".toMediaType())
        assertThat(body2.string()).isEqualTo(text)
    }

    @Test
    fun unicodeByteString() {
        val text = "eile oli oliiviõli".encodeToByteString(Charsets.UTF_8)
        val body = ClientResponseBody.create(text)
        assertThat(body.byteString()).isEqualTo(text)
        val body2 = text.toResponseBody()
        assertThat(body2.byteString()).isEqualTo(text)
    }

    @Test
    fun unicodeByteStringWithCharset() {
        val text = "eile oli oliiviõli".encodeToByteString(Charsets.UTF_8)
        val body = ClientResponseBody.create(text, "text/plain; charset=EBCDIC".toMediaType())
        assertThat(body.byteString()).isEqualTo(text)
        val body2 = text.toResponseBody("text/plain; charset=EBCDIC".toMediaType())
        assertThat(body2.byteString()).isEqualTo(text)
    }

    @Test
    fun unicodeUtf8() {
        val text = "eile oli oliiviõli".encodeToUtf8()
        val body = ClientResponseBody.create(text)
        assertThat(body.utf8()).isEqualTo(text)
    }

    @Test
    fun unicodeUtf8WithCharset() {
        val text = "eile oli oliiviõli".encodeToUtf8()
        val body = ClientResponseBody.create(text, "text/plain; charset=UTF-8".toMediaType())
        assertThat(body.utf8()).isEqualTo(text)
    }

    @Test
    fun unicodeUtf8WithUnsupportedCharset() {
        val text = "eile oli oliiviõli".encodeToUtf8()
        assertFailsWith<IllegalArgumentException> {
            text.toResponseBody("text/plain; charset=UTF-16".toMediaType())
        }.also { expected ->
            assertThat(expected.message).isEqualTo(
                "Invalid charset for Utf8 byte string: UTF-16",
            )
        }
    }

    @Test
    fun unicodeBytes() {
        val text = "eile oli oliiviõli".encodeToByteArray()
        val body = ClientResponseBody.create(text)
        assertThat(body.bytes()).isEqualTo(text)
        val body2 = text.toResponseBody()
        assertThat(body2.bytes()).isEqualTo(text)
    }

    @Test
    fun unicodeBytesWithCharset() {
        val text = "eile oli oliiviõli".encodeToByteArray()
        val body = ClientResponseBody.create(text, "text/plain; charset=EBCDIC".toMediaType())
        assertThat(body.bytes()).isEqualTo(text)
        val body2 = text.toResponseBody("text/plain; charset=EBCDIC".toMediaType())
        assertThat(body2.bytes()).isEqualTo(text)
    }

    @Test
    fun stringEmpty() {
        val body = body("")
        assertThat(body.string()).isEqualTo("")
    }

    @Test
    fun stringLooksLikeBomButTooShort() {
        val body = body("000048")
        assertThat(body.string()).isEqualTo("\u0000\u0000H")
    }

    @Test
    fun stringDefaultsToUtf8() {
        val body = body("68656c6c6f")
        assertThat(body.string()).isEqualTo("hello")
    }

    @Test
    fun stringExplicitCharset() {
        val body = body("00000068000000650000006c0000006c0000006f", "utf-32be")
        assertThat(body.string()).isEqualTo("hello")
    }

    @Test
    fun stringUnsupportedExplicitCharsetToUtf8() {
        val body = body("00000068000000650000006c0000006c0000006f", "utf-32be")
        assertFailsWith<UnsupportedCharsetException> {
            body.utf8()
        }
    }

    @Test
    fun stringBomOverridesExplicitCharset() {
        val body = body("0000feff00000068000000650000006c0000006c0000006f", "utf-8")
        assertThat(body.string()).isEqualTo("hello")
    }

    @Test
    fun stringBomUtf8() {
        val body = body("efbbbf68656c6c6f")
        assertThat(body.string()).isEqualTo("hello")
    }

    @Test
    fun stringBomUtf8SupportedToUtf8() {
        val body = body("efbbbf68656c6c6f")
        assertThat(body.utf8()).isEqualTo("hello".encodeToUtf8())
    }

    @Test
    fun stringBomUtf16Be() {
        val body = body("feff00680065006c006c006f")
        assertThat(body.string()).isEqualTo("hello")
    }

    @Test
    fun stringBomUtf16BeUnsupportedToUtf8() {
        val body = body("feff00680065006c006c006f")
        assertFailsWith<UnsupportedCharsetException> {
            body.utf8()
        }
    }

    @Test
    fun stringBomUtf16Le() {
        val body = body("fffe680065006c006c006f00")
        assertThat(body.string()).isEqualTo("hello")
    }

    @Test
    fun stringBomUtf32Be() {
        val body = body("0000feff00000068000000650000006c0000006c0000006f")
        assertThat(body.string()).isEqualTo("hello")
    }

    @Test
    fun stringBomUtf32Le() {
        val body = body("fffe000068000000650000006c0000006c0000006f000000")
        assertThat(body.string()).isEqualTo("hello")
    }

    @Test
    fun stringClosesUnderlyingSource() {
        val closed = AtomicBoolean()
        val body: ClientResponseBody =
            object : ClientResponseBody() {
                override fun contentType(): MediaType? {
                    return null
                }

                override fun contentByteSize(): Long {
                    return 5
                }

                override fun reader(): Reader {
                    val source = Buffer().write("hello")
                    return object : ForwardingRawReader(source) {
                        override fun close() {
                            closed.set(true)
                            super.close()
                        }
                    }.buffered()
                }
            }
        assertThat(body.string()).isEqualTo("hello")
        assertThat(closed.get()).isTrue()
    }

    @Test
    fun readerEmpty() {
        val body = body("")
        assertThat(exhaust(body.charStream())).isEqualTo("")
    }

    @Test
    fun readerLooksLikeBomButTooShort() {
        val body = body("000048")
        assertThat(exhaust(body.charStream())).isEqualTo("\u0000\u0000H")
    }

    @Test
    fun readerDefaultsToUtf8() {
        val body = body("68656c6c6f")
        assertThat(exhaust(body.charStream())).isEqualTo("hello")
    }

    @Test
    fun readerExplicitCharset() {
        val body = body("00000068000000650000006c0000006c0000006f", "utf-32be")
        assertThat(exhaust(body.charStream())).isEqualTo("hello")
    }

    @Test
    fun readerBomUtf8() {
        val body = body("efbbbf68656c6c6f")
        assertThat(exhaust(body.charStream())).isEqualTo("hello")
    }

    @Test
    fun readerBomUtf16Be() {
        val body = body("feff00680065006c006c006f")
        assertThat(exhaust(body.charStream())).isEqualTo("hello")
    }

    @Test
    fun readerBomUtf16Le() {
        val body = body("fffe680065006c006c006f00")
        assertThat(exhaust(body.charStream())).isEqualTo("hello")
    }

    @Test
    fun readerBomUtf32Be() {
        val body = body("0000feff00000068000000650000006c0000006c0000006f")
        assertThat(exhaust(body.charStream())).isEqualTo("hello")
    }

    @Test
    fun readerBomUtf32Le() {
        val body = body("fffe000068000000650000006c0000006c0000006f000000")
        assertThat(exhaust(body.charStream())).isEqualTo("hello")
    }

    @Test
    fun readerClosedBeforeBomClosesUnderlyingSource() {
        val closed = AtomicBoolean()
        val body: ClientResponseBody =
            object : ClientResponseBody() {
                override fun contentType(): MediaType? {
                    return null
                }

                override fun contentByteSize(): Long {
                    return 5
                }

                override fun reader(): Reader {
                    val body = body("fffe680065006c006c006f00")
                    return object : ForwardingRawReader(body.reader()) {
                        override fun close() {
                            closed.set(true)
                            super.close()
                        }
                    }.buffered()
                }
            }
        body.charStream().close()
        assertThat(closed.get()).isTrue()
    }

    @Test
    fun readerClosedAfterBomClosesUnderlyingSource() {
        val closed = AtomicBoolean()
        val body: ClientResponseBody =
            object : ClientResponseBody() {
                override fun contentType(): MediaType? {
                    return null
                }

                override fun contentByteSize(): Long {
                    return 5
                }

                override fun reader(): Reader {
                    val body = body("fffe680065006c006c006f00")
                    return object : ForwardingRawReader(body.reader()) {
                        override fun close() {
                            closed.set(true)
                            super.close()
                        }
                    }.buffered()
                }
            }
        val reader = body.charStream()
        assertThat(reader.read()).isEqualTo('h'.code)
        reader.close()
        assertThat(closed.get()).isTrue()
    }

    @Test
    fun sourceSeesBom() {
        val body = "efbbbf68656c6c6f".decodeHex().toResponseBody()
        val source = body.reader()
        assertThat(source.readByte() and 0xff).isEqualTo(0xef)
        assertThat(source.readByte() and 0xff).isEqualTo(0xbb)
        assertThat(source.readByte() and 0xff).isEqualTo(0xbf)
        assertThat(source.readString()).isEqualTo("hello")
    }

    @Test
    fun bytesEmpty() {
        val body = body("")
        assertThat(body.bytes().size).isEqualTo(0)
    }

    @Test
    fun bytesSeesBom() {
        val body = body("efbbbf68656c6c6f")
        val bytes = body.bytes()
        assertThat(bytes[0] and 0xff).isEqualTo(0xef)
        assertThat(bytes[1] and 0xff).isEqualTo(0xbb)
        assertThat(bytes[2] and 0xff).isEqualTo(0xbf)
        assertThat(String(bytes, 3, 5, StandardCharsets.UTF_8)).isEqualTo("hello")
    }

    @Test
    fun bytesClosesUnderlyingSource() {
        val closed = AtomicBoolean()
        val body: ClientResponseBody =
            object : ClientResponseBody() {
                override fun contentType(): MediaType? {
                    return null
                }

                override fun contentByteSize(): Long {
                    return 5
                }

                override fun reader(): Reader {
                    val source = Buffer().write("hello")
                    return object : ForwardingRawReader(source) {
                        override fun close() {
                            closed.set(true)
                            super.close()
                        }
                    }.buffered()
                }
            }
        assertThat(body.bytes().size).isEqualTo(5)
        assertThat(closed.get()).isTrue()
    }

    @Test
    fun bytesThrowsWhenLengthsDisagree() {
        val body: ClientResponseBody =
            object : ClientResponseBody() {
                override fun contentType(): MediaType? {
                    return null
                }

                override fun contentByteSize(): Long {
                    return 10
                }

                override fun reader(): Reader {
                    return Buffer().write("hello")
                }
            }
        assertFailsWith<JayoException> {
            body.bytes()
        }.also { expected ->
            assertThat(expected.message).isEqualTo(
                "Content-Length (10) and stream length (5) disagree",
            )
        }
    }

    @Test
    fun bytesThrowsMoreThanIntMaxValue() {
        val body: ClientResponseBody =
            object : ClientResponseBody() {
                override fun contentType(): MediaType? {
                    return null
                }

                override fun contentByteSize(): Long {
                    return Int.MAX_VALUE + 1L
                }

                override fun reader(): Reader {
                    throw AssertionError()
                }
            }
        assertFailsWith<JayoException> {
            body.bytes()
        }.also { expected ->
            assertThat(expected.message).isEqualTo(
                "Cannot buffer entire body for content byte byteSize: 2147483648",
            )
        }
    }

    @Test
    fun byteStringEmpty() {
        val body = body("")
        assertThat(body.byteString()).isEqualTo(ByteString.EMPTY)
    }

    @Test
    fun byteStringSeesBom() {
        val body = body("efbbbf68656c6c6f")
        val actual = body.byteString()
        val expected: ByteString = "efbbbf68656c6c6f".decodeHex()
        assertThat(actual).isEqualTo(expected)
    }

    @Test
    fun byteStringClosesUnderlyingSource() {
        val closed = AtomicBoolean()
        val body: ClientResponseBody =
            object : ClientResponseBody() {
                override fun contentType(): MediaType? {
                    return null
                }

                override fun contentByteSize(): Long {
                    return 5
                }

                override fun reader(): Reader {
                    val source = Buffer().write("hello")
                    return object : ForwardingRawReader(source) {
                        override fun close() {
                            closed.set(true)
                            super.close()
                        }
                    }.buffered()
                }
            }
        assertThat(body.byteString().byteSize()).isEqualTo(5)
        assertThat(closed.get()).isTrue()
    }

    @Test
    fun byteStringThrowsWhenLengthsDisagree() {
        val body: ClientResponseBody =
            object : ClientResponseBody() {
                override fun contentType(): MediaType? {
                    return null
                }

                override fun contentByteSize(): Long {
                    return 10
                }

                override fun reader(): Reader {
                    return Buffer().write("hello")
                }
            }
        assertFailsWith<JayoException> {
            body.byteString()
        }.also { expected ->
            assertThat(expected.message).isEqualTo(
                "Content-Length (10) and stream length (5) disagree",
            )
        }
    }

    @Test
    fun byteStringThrowsMoreThanIntMaxValue() {
        val body: ClientResponseBody =
            object : ClientResponseBody() {
                override fun contentType(): MediaType? {
                    return null
                }

                override fun contentByteSize(): Long {
                    return Int.MAX_VALUE + 1L
                }

                override fun reader(): Reader {
                    throw AssertionError()
                }
            }
        assertFailsWith<JayoException> {
            body.byteString()
        }.also { expected ->
            assertThat(expected.message).isEqualTo(
                "Cannot buffer entire body for content byte byteSize: 2147483648",
            )
        }
    }

    @Test
    fun byteStreamEmpty() {
        val body = body("")
        val bytes = body.byteStream()
        assertThat(bytes.read()).isEqualTo(-1)
    }

    @Test
    fun byteStreamSeesBom() {
        val body = body("efbbbf68656c6c6f")
        val bytes = body.byteStream()
        assertThat(bytes.read()).isEqualTo(0xef)
        assertThat(bytes.read()).isEqualTo(0xbb)
        assertThat(bytes.read()).isEqualTo(0xbf)
        assertThat(exhaust(InputStreamReader(bytes, StandardCharsets.UTF_8))).isEqualTo("hello")
    }

    @Test
    fun byteStreamClosesUnderlyingSource() {
        val closed = AtomicBoolean()
        val body: ClientResponseBody =
            object : ClientResponseBody() {
                override fun contentType(): MediaType? {
                    return null
                }

                override fun contentByteSize(): Long {
                    return 5
                }

                override fun reader(): Reader {
                    val source = Buffer().write("hello")
                    return object : ForwardingRawReader(source) {
                        override fun close() {
                            closed.set(true)
                            super.close()
                        }
                    }.buffered()
                }
            }
        body.byteStream().close()
        assertThat(closed.get()).isTrue()
    }

    @Test
    fun unicodeTextWithUnsupportedEncoding() {
        val text = "eile oli oliiviõli"
        val body = text.toResponseBody("text/plain; charset=unknown".toMediaType())
        assertThat(body.string()).isEqualTo(text)
    }

    companion object {
        @JvmOverloads
        fun body(
            hex: String,
            charset: String? = null,
        ): ClientResponseBody {
            val mediaType = if (charset == null) null else "any/thing; charset=$charset".toMediaType()
            return hex.decodeHex().toResponseBody(mediaType)
        }

        fun exhaust(reader: java.io.Reader): String {
            val builder = StringBuilder()
            val buf = CharArray(10)
            var read: Int
            while (reader.read(buf).also { read = it } != -1) {
                builder.appendRange(buf, 0, read)
            }
            return builder.toString()
        }
    }
}

abstract class ForwardingRawReader(
    private val delegate: RawReader,
) : RawReader {
    final override fun readAtMostTo(writer: Buffer, byteCount: Long) =
        delegate.readAtMostTo(writer, byteCount)

    override fun close() = delegate.close()
}
