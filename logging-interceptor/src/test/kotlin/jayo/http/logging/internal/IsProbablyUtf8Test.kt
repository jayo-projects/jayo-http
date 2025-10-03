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

package jayo.http.logging.internal

import jayo.Buffer
import jayo.http.logging.internal.RealHttpLoggingInterceptor.isProbablyUtf8
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IsProbablyUtf8Test {
    @Test
    fun isProbablyUtf8Tests() {
        assertThat(isProbablyUtf8(Buffer())).isTrue()
        assertThat(isProbablyUtf8(Buffer().write("abc"))).isTrue()
        assertThat(isProbablyUtf8(Buffer().write("new\r\nlines"))).isTrue()
        assertThat(isProbablyUtf8(Buffer().write("white\t space"))).isTrue()
        assertThat(isProbablyUtf8(Buffer().writeByte(0x80.toByte()))).isTrue()
        assertThat(isProbablyUtf8(Buffer().writeByte(0x00))).isFalse()
        assertThat(isProbablyUtf8(Buffer().writeByte(0xc0.toByte()))).isFalse()
    }
}
