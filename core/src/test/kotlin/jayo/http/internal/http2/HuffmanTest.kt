/*
 * Copyright (c) 2026-present, pull-vert and Jayo contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 *
 * Forked from OkHttp (https://github.com/square/okhttp), original copyright is below
 *
 * Copyright 2013 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jayo.http.internal.http2

import jayo.Buffer
import jayo.bytestring.ByteString
import jayo.bytestring.encodeToByteString
import jayo.bytestring.toByteString
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.*

/** Original version of this class was lifted from `com.twitter.hpack.HuffmanTest`.  */
class HuffmanTest {
    @Test
    fun roundTripForRequestAndResponse() {
        val s = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        for (i in s.indices) {
            assertRoundTrip(s.substring(0, i).encodeToByteString())
        }
        val random = Random(123456789L)
        val buf = ByteArray(4096)
        random.nextBytes(buf)
        assertRoundTrip(buf.toByteString())
    }

    private fun assertRoundTrip(data: ByteString) {
        val encodeBuffer = Buffer()
        Huffman.encode(data, encodeBuffer)
        assertThat(Huffman.encodedLength(data).toLong()).isEqualTo(encodeBuffer.bytesAvailable())
        val decodeBuffer = Buffer()
        Huffman.decode(encodeBuffer, encodeBuffer.bytesAvailable(), decodeBuffer)
        assertThat(data).isEqualTo(decodeBuffer.readByteString())
    }
}
