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
import jayo.RawReader
import jayo.buffered
import jayo.http.tools.JayoHttpUtils.isProbablyUtf8
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IsProbablyUtf8Test {
    @Test
    fun isProbablyUtf8() {
        assertThat(isProbablyUtf8(Buffer(), 16L)).isTrue()
        assertThat(isProbablyUtf8(Buffer().write("abc"), 16L)).isTrue()
        assertThat(isProbablyUtf8(Buffer().write("new\r\nlines"), 16L)).isTrue()
        assertThat(isProbablyUtf8(Buffer().write("white\t space"), 16L)).isTrue()
        assertThat(isProbablyUtf8(Buffer().write("Слава Україні!"), 16L)).isTrue()
        assertThat(isProbablyUtf8(Buffer().writeByte(0x80.toByte()), 16L)).isTrue()
        assertThat(isProbablyUtf8(Buffer().writeByte(0x00), 16L)).isFalse()
        assertThat(isProbablyUtf8(Buffer().writeByte(0xc0.toByte()), 16L)).isFalse()
    }

    @Test
    fun doesNotConsumeBuffer() {
        val buffer = Buffer()
        buffer.write("hello ".repeat(1024))
        assertThat(isProbablyUtf8(buffer, 100L)).isTrue()
        assertThat(buffer.readString()).isEqualTo("hello ".repeat(1024))
    }

    /** Confirm [isProbablyUtf8] doesn't attempt to read the entire stream. */
    @Test
    fun doesNotReadEntireSource() {
        val unlimitedRawReader =
            object : RawReader {
                override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
                    sink.write("a".repeat(byteCount.toInt()))
                    return byteCount
                }

                override fun close() {
                }
            }

        assertThat(isProbablyUtf8(unlimitedRawReader.buffered(), 1L)).isTrue()
        assertThat(isProbablyUtf8(unlimitedRawReader.buffered(), 1024L)).isTrue()
        assertThat(isProbablyUtf8(unlimitedRawReader.buffered(), 1024L * 1024L)).isTrue()
    }
}
